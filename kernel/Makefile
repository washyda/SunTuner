# SPDX-License-Identifier: GPL-2.0-only
ifeq ($(SUN_TUNER_PLATFORM),sm8850)
obj-m := sm8750_stress_qos.o sm8750_scmi_plan_ro.o
CFLAGS_sm8750_scmi_plan_ro.o += -DSUNTUNER_SM8850
else
obj-m := sm8750_acd_observer.o sm8750_apns_trigger.o sm8750_scmi_plan_ro.o sm8750_pvc_corr_ro.o sm8750_stress_qos.o
endif
CFLAGS_sm8750_scmi_plan_ro.o += -I$(SCMI_PROTOCOL_DIR)
