// SPDX-License-Identifier: GPL-2.0-only
/* Temporary frequency bounds for each PJZ110 cpufreq policy during a benchmark. */
#include <linux/cpufreq.h>
#include <linux/module.h>
#include <linux/pm_qos.h>

static unsigned int pdp0_khz;
static unsigned int pdp1_khz;
module_param(pdp0_khz, uint, 0400);
module_param(pdp1_khz, uint, 0400);

static struct {
	struct cpufreq_policy *policy;
	struct freq_qos_request ceiling;
	struct freq_qos_request floor;
} locks[2];

static void clear_locks(void)
{
	unsigned int i;

	for (i = 0; i < ARRAY_SIZE(locks); i++) {
		if (!locks[i].policy)
			continue;
		if (freq_qos_request_active(&locks[i].floor))
			freq_qos_remove_request(&locks[i].floor);
		if (freq_qos_request_active(&locks[i].ceiling))
			freq_qos_remove_request(&locks[i].ceiling);
		cpufreq_cpu_put(locks[i].policy);
		locks[i].policy = NULL;
	}
}

static int add_lock(unsigned int index, unsigned int khz)
{
	unsigned int core = index ? 6 : 0;
	struct cpufreq_policy *policy;
	int rc;

	if (!khz)
		return 0;

	policy = cpufreq_cpu_get(core);
	if (!policy && index == 1)
		policy = cpufreq_cpu_get(7);
	if (!policy)
		return -ENODEV;

	/* Other clients still participate in the policy constraints. */
	if (khz < policy->cpuinfo.min_freq || khz > policy->cpuinfo.max_freq) {
		rc = -EINVAL;
		goto put;
	}
	rc = freq_qos_add_request(&policy->constraints, &locks[index].ceiling,
				  FREQ_QOS_MAX, khz);
	if (rc < 0)
		goto put;

	locks[index].policy = policy;
	rc = freq_qos_add_request(&policy->constraints, &locks[index].floor,
				  FREQ_QOS_MIN, khz);
	return rc < 0 ? rc : 0;
put:
	cpufreq_cpu_put(policy);
	return rc;
}

static int __init sm8750_stress_qos_init(void)
{
	int rc;

	if (!pdp0_khz && !pdp1_khz)
		return -EINVAL;
	rc = add_lock(0, pdp0_khz);
	if (!rc)
		rc = add_lock(1, pdp1_khz);
	if (rc)
		clear_locks();
	return rc;
}

module_init(sm8750_stress_qos_init);
module_exit(clear_locks);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Temporary CPU frequency floor and ceiling for PJZ110 benchmark");
