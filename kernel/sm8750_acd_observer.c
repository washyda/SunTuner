// SPDX-License-Identifier: GPL-2.0-only
#include <linux/io.h>
#include <linux/ktime.h>
#include <linux/module.h>
#include <linux/moduleparam.h>

#define PDP_APNS_SHARED 0x81f81000
#define PDP_APNS_STRIDE 0x60
#define PDP0_BOOT_COOKIE 0x200
#define PDP_BOOT_COOKIE_STRIDE 0x40
#define ACD_ASYNC_HEADER 0x20313
#define ACD_ASYNC_LENGTH 0x24
#define ACD_ALGORITHM 0x646361
#define ACD_COMPLETION_PARAM 3
#define SNAPSHOT_WORDS 11

static unsigned int pdp;
module_param(pdp, uint, 0400);

static int read_word(void __iomem *address, u32 *value)
{
	__le32 raw;
	int error = 0;

	__raw_get_mem("ldr", raw, (__force __le32 *)address, error, K);
	if (error)
		return -EFAULT;
	*value = le32_to_cpu(raw);
	return 0;
}

static int collect_snapshot(char *buf, const struct kernel_param *kp)
{
	/* Selected APNS completion mailbox, then three boot cookie fields. */
	static const u16 offsets[SNAPSHOT_WORDS] = {
		0x04, 0x10, 0x14, 0x18, 0x1c, 0x20, 0x28, 0x2c,
		PDP0_BOOT_COOKIE, PDP0_BOOT_COOKIE + 0x30, PDP0_BOOT_COOKIE + 0x34,
	};
	u32 first[SNAPSHOT_WORDS], second[SNAPSHOT_WORDS];
	u8 __iomem *regs;
	bool stable, acd_payload;
	unsigned int i, pass;
	u32 *words;
	int rc = -EFAULT;

	regs = ioremap_wc(PDP_APNS_SHARED, 0x278);
	if (!regs)
		return -ENOMEM;

	for (pass = 0; pass < 2; pass++) {
		words = pass ? second : first;
		for (i = 0; i < ARRAY_SIZE(offsets); i++) {
			unsigned int stride = offsets[i] < PDP0_BOOT_COOKIE ?
				PDP_APNS_STRIDE : PDP_BOOT_COOKIE_STRIDE;

			if (read_word(regs + offsets[i] + pdp * stride, &words[i]))
				goto out;
		}
		rmb();
	}
	stable = !memcmp(first, second, sizeof(first));
	acd_payload = stable && first[2] == ACD_ASYNC_LENGTH &&
		first[3] == ACD_ASYNC_HEADER && first[5] == ACD_ALGORITHM &&
		first[6] == ACD_COMPLETION_PARAM && first[4] == first[7];

	rc = scnprintf(buf, PAGE_SIZE,
		"ns=%llu stable=%u state=%#x flags=%#x len=%u header=%#x "
		"status=%d algorithm=%#x param=%u result=%d acd_payload=%u "
		"cookie_state=%u cookie_magic=%#x cookie_version=%u\n",
		ktime_get_ns(), stable, first[0], first[1], first[2], first[3],
		(s32)first[4], first[5], first[6], (s32)first[7], acd_payload,
		first[8] >> 30, first[9], first[10] & 0xffff);
out:
	iounmap(regs);
	return rc;
}

static const struct kernel_param_ops snapshot_ops = {
	.get = collect_snapshot,
};

module_param_cb(snapshot, &snapshot_ops, NULL, 0444);

static int __init sm8750_acd_observer_init(void)
{
	return pdp > 1 ? -EINVAL : 0;
}

static void __exit sm8750_acd_observer_exit(void)
{
}

module_init(sm8750_acd_observer_init);
module_exit(sm8750_acd_observer_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Read-only view of a PDP APNS notification and boot cookie");
