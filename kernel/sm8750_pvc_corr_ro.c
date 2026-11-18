// SPDX-License-Identifier: GPL-2.0-only
#include <linux/cpufreq.h>
#include <linux/delay.h>
#include <linux/init.h>
#include <linux/ktime.h>
#include <linux/module.h>
#include <linux/of.h>
#include <linux/spmi.h>

static const struct {
	const char *label;
	const char *path;
	u8 sid;
	u16 base;
} endpoints[] = {
	{ "pvc0_default", "/soc/qcom,spmi@c42d000/qcom,pm8550vs@9", 9, 0xa400 },
	{ "pvc1_shared", "/soc/qcom,spmi@c42d000/qcom,pm8550ve@5", 5, 0x9b00 },
	{ "pvc2_default", "/soc/qcom,spmi@c42d000/qcom,pm8550ve@5", 5, 0xaa00 },
	{ "pvc3_default", "/soc/qcom,spmi@c42d000/qcom,pm8550ve@5", 5, 0xad00 },
};

static int __init pvc_status_init(void)
{
	struct spmi_device *devices[ARRAY_SIZE(endpoints)] = {};
	unsigned int i, sample;

	for (i = 0; i < ARRAY_SIZE(endpoints); i++) {
		struct device_node *node = of_find_node_by_path(endpoints[i].path);
		struct spmi_device *spmi = node ? spmi_device_from_of(node) : NULL;

		of_node_put(node);
		if (!spmi || !spmi->ctrl || spmi->ctrl->nr != 0 ||
		    spmi->usid != endpoints[i].sid) {
			pr_info("sm8750_pvc_corr: %s device binding mismatch\n",
				endpoints[i].label);
			if (spmi)
				spmi_device_put(spmi);
			continue;
		}
		devices[i] = spmi;
	}
	for (sample = 0; sample < 192; sample++) {
		for (i = 0; i < ARRAY_SIZE(endpoints); i++) {
			struct spmi_device *spmi = devices[i];
			u8 id[2] = {}, voltage[4] = {}, before = 0, after = 0;
			u16 base = endpoints[i].base;
			int id_rc, before_rc, after_rc, voltage_rc;
			u64 t0, t1;
			unsigned int before0, before6, after0, after6;

			if (!spmi)
				continue;
			id_rc = spmi_ext_register_readl(spmi, base + 0x04, id, sizeof(id));
			if (id_rc || id[0] != 0x1c || id[1] != 0x11) {
				pr_info("sm8750_pvc_corr: %s unexpected id=%02x%02x rc=%d\n",
					endpoints[i].label, id[0], id[1], id_rc);
				continue;
			}
			before0 = cpufreq_quick_get(0);
			before6 = cpufreq_quick_get(6);
			t0 = ktime_get_ns();
			before_rc = spmi_ext_register_readl(spmi, base + 0x08, &before, 1);
			voltage_rc = spmi_ext_register_readl(spmi, base + 0x40,
							    voltage, sizeof(voltage));
			after_rc = spmi_ext_register_readl(spmi, base + 0x08, &after, 1);
			t1 = ktime_get_ns();
			after0 = cpufreq_quick_get(0);
			after6 = cpufreq_quick_get(6);
			pr_info("sm8750_pvc_corr: n=%u %s sid=%u page=%02x id=%02x%02x rc=%d,%d,%d status=%02x,%02x set=%02x%02x valid=%02x%02x t0_ns=%llu t1_ns=%llu kHz0=%u,%u kHz6=%u,%u\n",
				sample, endpoints[i].label, spmi->usid, base >> 8,
				id[0], id[1], before_rc, voltage_rc, after_rc,
				before, after, voltage[0], voltage[1],
				voltage[2], voltage[3], t0, t1,
				before0, after0, before6, after6);
		}
		if (sample < 191)
			msleep(19);
	}
	for (i = 0; i < ARRAY_SIZE(endpoints); i++)
		if (devices[i])
			spmi_device_put(devices[i]);
	return 0;
}

static void __exit pvc_status_exit(void)
{
}

module_init(pvc_status_init);
module_exit(pvc_status_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Read SM8750 XBL PVC codes with cached cpufreq requests");
