// SPDX-License-Identifier: GPL-2.0-only
/*
 * One-shot APNS requests for the fixed native SLT and ACD handlers.
 * This module accepts no voltage value and sends no arbitrary SCMI payload.
 */
#include <linux/delay.h>
#include <linux/init.h>
#include <linux/io.h>
#include <linux/ktime.h>
#include <linux/module.h>
#include <linux/random.h>

#define APNS_CHANNEL_BYTES 0x60
#define TX_CMD_HIGH 0x104
#define SCMI_DOORBELL 0x494d4353
#define SCMI_CHANNEL_FREE BIT(0)
#define SCMI_CHANNEL_ERROR BIT(1)
#define SCMI_PROTOCOL_QCOM 0x80
#define SCMI_SET_PARAM 0x10
#define SCMI_START_ACTIVITY 0x12
#define SCMI_TOKEN_SHIFT 18
#define SCMI_TOKEN_MASK 0x3ff
#define SCMI_HEADER(protocol, command, token) \
	(((protocol) << 10) | (command) | ((token) << SCMI_TOKEN_SHIFT))

#define ALGORITHM_SLT 0x00746c73
#define ALGORITHM_ACD 0x00646361
#define SLT_ENABLE_PARAM 4
#define ACD_START_PARAM 2

#define STATUS_OFFSET 0x04
#define FLAGS_OFFSET 0x10
#define LENGTH_OFFSET 0x14
#define HEADER_OFFSET 0x18
#define PAYLOAD_OFFSET 0x1c
#define RESPONSE_VALUE_OFFSET 0x20
#define POLL_TIMEOUT_US 1000000

static const struct {
	phys_addr_t shmem;
	phys_addr_t tx_mbox;
} targets[] = {
	{ 0x81f810c0, 0x18980000 },
	{ 0x81f81120, 0x19980000 },
};

/* pdp selects one APNS channel; action 1=SLT, 2=ACD. */
static unsigned int pdp;
module_param(pdp, uint, 0400);
static unsigned int action;
module_param(action, uint, 0400);

static int read_word(void __iomem *address, u32 *value)
{
	__le32 raw;
	int error = 0;

	__raw_get_mem("ldr", raw, (__force __le32 *)address, error, K);
	if (error)
		return -EFAULT;
	*value = le32_to_cpu(raw);
	rmb();
	return 0;
}

static int write_word(void __iomem *address, u32 value)
{
	__le32 raw = cpu_to_le32(value);
	int error = 0;

	__raw_put_mem("str", raw, (__force __le32 *)address, error, K);
	return error;
}

static int poll_reply(u8 __iomem *shmem, u32 expected, ktime_t start)
{
	u32 state, header, length, status, value = 0;
	s64 elapsed;
	int rc;

	for (;;) {
		rc = read_word(shmem + STATUS_OFFSET, &state);
		if (rc)
			return rc;
		elapsed = ktime_us_delta(ktime_get(), start);
		if (state & (SCMI_CHANNEL_FREE | SCMI_CHANNEL_ERROR))
			break;
		if (elapsed >= POLL_TIMEOUT_US) {
			pr_err("sm8750_apns_trigger: pdp=%u reply timeout state=%#x elapsed_us=%lld\n",
			       pdp, state, elapsed);
			return -ETIMEDOUT;
		}
		usleep_range(50, 100);
	}

	rc = read_word(shmem + HEADER_OFFSET, &header);
	if (rc)
		return rc;
	rc = read_word(shmem + LENGTH_OFFSET, &length);
	if (rc)
		return rc;
	rc = read_word(shmem + PAYLOAD_OFFSET, &status);
	if (rc)
		return rc;
	if (length == 12) {
		rc = read_word(shmem + RESPONSE_VALUE_OFFSET, &value);
		if (rc)
			return rc;
	}

	pr_info("sm8750_apns_trigger: pdp=%u reply state=%#x expected=%#x header=%#x length=%u status=%d value=%#x elapsed_us=%lld\n",
		pdp, state, expected, header, length, (s32)status, value, elapsed);

	if (state != SCMI_CHANNEL_FREE || header != expected ||
	    (length != 8 && length != 12))
		return -EPROTO;
	if (status)
		return -EREMOTEIO;
	return 0;
}

static int send_fixed_request(void)
{
	const bool slt = action == 1;
	const u32 command = slt ? SCMI_SET_PARAM : SCMI_START_ACTIVITY;
	const u32 algorithm = slt ? ALGORITHM_SLT : ALGORITHM_ACD;
	const u32 parameter = slt ? SLT_ENABLE_PARAM : ACD_START_PARAM;
	const u32 words[] = { 0, algorithm, 0, parameter };
	u8 __iomem *shmem, *doorbell;
	u32 token, header, state, flags, length, prior_header;
	int rc;
	ktime_t start, flushed;
	unsigned int i;

	shmem = ioremap_wc(targets[pdp].shmem, APNS_CHANNEL_BYTES);
	if (!shmem)
		return -ENOMEM;
	doorbell = ioremap(targets[pdp].tx_mbox + TX_CMD_HIGH, sizeof(u32));
	if (!doorbell) {
		iounmap(shmem);
		return -ENOMEM;
	}

	rc = read_word(shmem + STATUS_OFFSET, &state);
	if (!rc)
		rc = read_word(shmem + FLAGS_OFFSET, &flags);
	if (!rc)
		rc = read_word(shmem + LENGTH_OFFSET, &length);
	if (!rc)
		rc = read_word(shmem + HEADER_OFFSET, &prior_header);
	if (rc)
		goto out;
	pr_info("sm8750_apns_trigger: pdp=%u phase=preflight action=%s state=%#x flags=%#x length=%u header=%#x\n",
		pdp, slt ? "slt" : "acd", state, flags, length,
		prior_header);
	if (state != SCMI_CHANNEL_FREE) {
		rc = -EBUSY;
		goto out;
	}

	token = get_random_u32() & SCMI_TOKEN_MASK;
	if (token == ((prior_header >> SCMI_TOKEN_SHIFT) & SCMI_TOKEN_MASK))
		token = (token + 1) & SCMI_TOKEN_MASK;
	header = SCMI_HEADER(SCMI_PROTOCOL_QCOM, command, token);

	/* Claim this synchronous APNS buffer and send only the fixed 16-byte header payload. */
	rc = write_word(shmem + STATUS_OFFSET, 0);
	if (!rc)
		rc = write_word(shmem + FLAGS_OFFSET, 0);
	if (!rc)
		rc = write_word(shmem + LENGTH_OFFSET, 4 + sizeof(words));
	if (!rc)
		rc = write_word(shmem + HEADER_OFFSET, header);
	for (i = 0; !rc && i < ARRAY_SIZE(words); i++)
		rc = write_word(shmem + PAYLOAD_OFFSET + 4 * i, words[i]);
	if (rc)
		goto out;

	wmb();
	pr_info("sm8750_apns_trigger: pdp=%u phase=send action=%s header=%#x length=%zu parameter=%u\n",
		pdp, slt ? "slt" : "acd", header, 4 + sizeof(words), parameter);
	start = ktime_get();
	rc = write_word(doorbell, SCMI_DOORBELL);
	if (!rc) {
		dsb(sy);
		flushed = ktime_get();
		rc = poll_reply(shmem, header, start);
		pr_info("sm8750_apns_trigger: pdp=%u phase=doorbell action=%s header=%#x before_ns=%lld flushed_ns=%lld\n",
			pdp, slt ? "slt" : "acd", header,
			ktime_to_ns(start), ktime_to_ns(flushed));
	}
out:
	iounmap(doorbell);
	iounmap(shmem);
	return rc;
}

static int __init sm8750_apns_trigger_init(void)
{
	int rc;

	if (pdp >= ARRAY_SIZE(targets) || (action != 1 && action != 2))
		return -EINVAL;
	rc = send_fixed_request();
	pr_info("sm8750_apns_trigger: pdp=%u action=%s result=%d\n",
		pdp, action == 1 ? "slt" : "acd", rc);
	return rc;
}

static void __exit sm8750_apns_trigger_exit(void)
{
}

module_init(sm8750_apns_trigger_init);
module_exit(sm8750_apns_trigger_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("One-shot fixed-function SM8750 APNS SLT/ACD request");
