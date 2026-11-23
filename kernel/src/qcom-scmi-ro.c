// SPDX-License-Identifier: GPL-2.0-only
/*
 * Native SCMI PERF query client for SunTuner.
 * Requires the compatible kernel's drivers/firmware/arm_scmi/protocols.h.
 * Each snapshot read sends only VERSION, ATTRIBUTES and DESCRIBE_LEVELS.
 * Outputs estimated power and frequency, not a physical voltage reading.
 */
#include <linux/ktime.h>
#include <linux/module.h>
#include <linux/mutex.h>
#include <linux/scmi_protocol.h>
#include <linux/slab.h>
#include <linux/sysfs.h>

#include "protocols.h"

#define PLAN_DOMAINS 2U
#ifdef SUNTUNER_SM8850
#define PLAN_STATES 128U
#else
#define PLAN_STATES 16U
#endif
#define PLAN_RECORD_SIZE 20U
#define PLAN_DESCRIBE_LEVELS 0x04

struct plan_query {
	const struct scmi_protocol_handle *ph;
	struct mutex lock;
};

static int plan_attributes(const struct scmi_protocol_handle *ph)
{
	struct scmi_xfer *t;
	u32 attrs;
	int ret;

	ret = ph->xops->xfer_get_init(ph, PROTOCOL_ATTRIBUTES, 0, 16, &t);
	if (ret)
		return ret;
	ret = ph->xops->do_xfer(ph, t);
	if (ret)
		goto out;
	if (t->rx.len < 16) {
		ret = -EPROTO;
		goto out;
	}
	attrs = get_unaligned_le32(t->rx.buf);
	/* This image reports two domains and power in microwatts. */
	if ((attrs & 0xffff) != PLAN_DOMAINS || ((attrs >> 16) & 3) != 2)
		ret = -EOPNOTSUPP;
out:
	ph->xops->xfer_put(ph, t);
	return ret;
}

static int plan_domain(const struct scmi_protocol_handle *ph, u32 domain,
		       char *buf, size_t *written)
{
	u32 index = 0;
	u16 remaining;
	int ret;

	do {
		struct scmi_xfer *t;
		const u8 *record;
		u16 returned, row;

		/* A zero receive size asks the core for its transport maximum. */
		ret = ph->xops->xfer_get_init(ph, PLAN_DESCRIBE_LEVELS, 8, 0, &t);
		if (ret)
			return ret;
		put_unaligned_le32(domain, t->tx.buf);
		put_unaligned_le32(index, (u8 *)t->tx.buf + 4);
		ret = ph->xops->do_xfer(ph, t);
		if (ret)
			goto release;
		if (t->rx.len < 4 + PLAN_RECORD_SIZE) {
			ret = -EPROTO;
			goto release;
		}
		returned = get_unaligned_le16(t->rx.buf);
		remaining = get_unaligned_le16((u8 *)t->rx.buf + 2);
#ifdef SUNTUNER_SM8850
		if (!returned || returned > (t->rx.len - 4) / PLAN_RECORD_SIZE ||
		    index + returned + remaining > PLAN_STATES) {
#else
		if (returned != 1 || remaining != PLAN_STATES - index - 1) {
#endif
			ret = -EPROTO;
			goto release;
		}
		record = (u8 *)t->rx.buf + 4;
		for (row = 0; row < returned; row++, index++, record += PLAN_RECORD_SIZE) {
			u32 perf = get_unaligned_le32(record);
			u32 power = get_unaligned_le32(record + 4);
			u16 latency = get_unaligned_le16(record + 8);
			u32 freq = get_unaligned_le32(record + 12);
			u32 level_index = get_unaligned_le32(record + 16);

			if (!freq || !power) {
				ret = -EPROTO;
				goto release;
			}
			if (PAGE_SIZE - *written < 128) {
				ret = -EOVERFLOW;
				goto release;
			}
			*written += sysfs_emit_at(buf, *written,
				"%u,%u,%u,%u,%u,%u,%u\n", domain, index, perf,
				power, latency, freq, level_index);
		}
release:
		ph->xops->xfer_put(ph, t);
		if (ret)
			return ret;
	} while (remaining);
	return 0;
}

static ssize_t snapshot_show(struct device *dev,
			     struct device_attribute *attr, char *buf)
{
	struct plan_query *query = dev_get_drvdata(dev);
	const struct scmi_protocol_handle *ph = query->ph;
	u64 start_ns;
	u32 version, domain;
	size_t written = 0;
	int ret;

	ret = mutex_lock_interruptible(&query->lock);
	if (ret)
		return ret;
	start_ns = ktime_get_ns();
	ret = ph->xops->version_get(ph, &version);
	if (ret)
		goto out;
	if (version != 0x40000) {
		ret = -EOPNOTSUPP;
		goto out;
	}
	ret = plan_attributes(ph);
	if (ret)
		goto out;
	written += sysfs_emit_at(buf, written,
		"# fresh_native_query=1,physical_voltage_measured=0,atomic_snapshot=0\n"
		"# start_ns=%llu,protocol_version=0x%x,power_unit=uW\n"
		"domain,query_index,perf_value,estimated_power_uW,latency_us,indicative_freq_kHz,level_index\n",
		(unsigned long long)start_ns, version);
	for (domain = 0; domain < PLAN_DOMAINS; domain++) {
		ret = plan_domain(ph, domain, buf, &written);
		if (ret)
			goto out;
	}
	if (PAGE_SIZE - written < 64) {
		ret = -EOVERFLOW;
		goto out;
	}
	written += sysfs_emit_at(buf, written, "# end_ns=%llu\n",
		(unsigned long long)ktime_get_ns());
out:
	mutex_unlock(&query->lock);
	/* A failed or incomplete query is returned as an error, never a table. */
	if (ret)
		return ret;
	return written;
}

static DEVICE_ATTR_RO(snapshot);

static struct attribute *plan_attrs[] = {
	&dev_attr_snapshot.attr,
	NULL,
};
ATTRIBUTE_GROUPS(plan);

static int plan_probe(struct scmi_device *sdev)
{
	struct scmi_protocol_handle *ph;
	struct plan_query *query;
	const void *ops;

	if (!sdev->handle)
		return -EPROBE_DEFER;
	ops = sdev->handle->devm_protocol_get(sdev, SCMI_PROTOCOL_PERF, &ph);
	if (IS_ERR(ops))
		return PTR_ERR(ops);
	if (!ph || !ph->xops || !ph->xops->version_get ||
	    !ph->xops->xfer_get_init || !ph->xops->do_xfer ||
	    !ph->xops->xfer_put)
		return -EOPNOTSUPP;
	query = devm_kzalloc(&sdev->dev, sizeof(*query), GFP_KERNEL);
	if (!query)
		return -ENOMEM;
	query->ph = ph;
	mutex_init(&query->lock);
	dev_set_drvdata(&sdev->dev, query);
	return 0;
}

/* A distinct name avoids claiming any existing cpufreq SCMI device. */
static const struct scmi_device_id plan_id_table[] = {
	{ .protocol_id = SCMI_PROTOCOL_PERF, .name = "scmi-plan-ro" },
	{ },
};
MODULE_DEVICE_TABLE(scmi, plan_id_table);

static struct scmi_driver plan_driver = {
	.name = "qcom-scmi-ro",
	.probe = plan_probe,
	.id_table = plan_id_table,
	.driver = { .dev_groups = plan_groups },
};
module_scmi_driver(plan_driver);

MODULE_DESCRIPTION("Qualcomm SCMI PERF read-only voltage plan query");
MODULE_LICENSE("GPL");
