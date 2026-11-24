"""Host-side CDT tests. Run with python3 app/src/test/native/test_cdt_key7.py."""

import hashlib
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest


TEST_DIR = Path(__file__).resolve().parent
SOURCE_DIR = TEST_DIR.parents[1] / "main" / "cpp"
PARTITION_SIZE = 131072
EMPTY = bytes(PARTITION_SIZE)
V5_MTP = bytes.fromhex("05 08 01 00 00 00 00")
V5_QRD = bytes.fromhex("05 0b 02 00 00 00 00")
V6_MTP = bytes.fromhex("06 08 01 00 00 00 00 00 00 00")
V6_QRD = bytes.fromhex("06 0b 02 00 00 00 00 00 00 00")
PLK110_V6_STOCK = bytes.fromhex("06 08 01 00 00 00 00 60 ff")
OLD_V6_MTP = bytes.fromhex("06 08 01 00 00 00 01 07 01")


def cdt_image(cdb0, *, second_block=None, second_offset=64):
    image = bytearray(PARTITION_SIZE)
    image[:4] = b"CDT\0"
    cdb0_offset = 18 if second_block is None else 22
    struct.pack_into("<HH", image, 14, cdb0_offset, len(cdb0))
    image[cdb0_offset:cdb0_offset + len(cdb0)] = cdb0
    if second_block is not None:
        struct.pack_into("<HH", image, 18, second_offset, len(second_block))
        image[second_offset:second_offset + len(second_block)] = second_block
    return bytes(image)


def firmware_key7(image):
    """Check the Key 7 bytes at the offsets derived from XBL, independently of cdt_tool."""
    offset, length = struct.unpack_from("<HH", image, 14)
    cdb0 = image[offset:offset + length]
    count_offset, key_start = {3: (5, 6), 4: (6, 7), 5: (6, 7), 6: (9, 10)}[cdb0[0]]
    if length < key_start or length < key_start + 2 * cdb0[count_offset]:
        raise ValueError("CDB0 does not cover the firmware's Key table")
    end = key_start + 2 * cdb0[count_offset]
    return dict(zip(cdb0[key_start:end:2], cdb0[key_start + 1:end:2])).get(7)


def platform_dtb(value, *, node=b"PlatformInfo"):
    """Minimal version-17 FDT containing the captured PlatformInfo property."""
    def words(*values):
        return struct.pack(f">{len(values)}I", *values)

    def padded(data):
        return data + b"\0" * (-len(data) % 4)

    def begin_node(name):
        return words(1) + padded(name + b"\0")

    structure = (
        begin_node(b"") + begin_node(b"soc") + begin_node(node)
        + words(3, len(value), 0) + padded(value) + words(2, 2, 2, 9)
    )
    strings = b"PlatformInfo\0"
    reserve_map = bytes(16)
    structure_offset = 40 + len(reserve_map)
    strings_offset = structure_offset + len(structure)
    header = words(
        0xD00DFEED, strings_offset + len(strings), structure_offset, strings_offset,
        40, 17, 16, 0, len(strings), len(structure),
    )
    return header + reserve_map + structure + strings


class CdtKey7Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        directory = tempfile.TemporaryDirectory(prefix="suntuner-key7-build-")
        cls.addClassCleanup(directory.cleanup)
        cls.tool = Path(directory.name) / "cdt_tool_tests"
        subprocess.run(
            [
                *shlex.split(os.environ.get("CC", "cc")),
                "-std=c17", "-O2", "-Wall", "-Wextra", "-Werror", "-I", str(SOURCE_DIR),
                str(TEST_DIR / "cdt_key7_driver.c"), str(SOURCE_DIR / "cdt_fdt.c"),
                "-o", str(cls.tool),
            ],
            check=True,
        )

    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="suntuner-key7-case-")
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        self.dev = root / "cdt.img"
        self.backup = root / "cdt-orig.bin"
        self.imported = root / "original.img"
        self.firmware = root / "xbl_config.img"
        self.dev.write_bytes(EMPTY)

    def call(self, *args, success=True):
        env = os.environ.copy()
        env["SUNTUNER_XBL_CONFIG"] = str(self.firmware)
        result = subprocess.run([self.tool, *args], env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        return result.stdout + result.stderr

    def status(self, identity=None, *, require_template=False):
        args = ("status", self.dev, self.backup) if identity is None else (
            "status-for-platform", self.dev, self.backup, *map(str, identity[:5]),
        )
        if require_template:
            args += ("--require-template",)
        return dict(line.split("=", 1) for line in self.call(*args).splitlines())

    def enable(self, identity=V6_MTP, *, success=True, require_template=False):
        args = ("--require-template",) if require_template else ()
        return self.call(
            "enable-for-platform", self.dev, self.backup, *map(str, identity[:5]), *args, success=success,
        )

    def restore(self, *options, success=True):
        return self.call("restore", self.dev, self.backup, *options, success=success)

    def write_firmware(self, *dtbs):
        # Qualcomm xbl_config can contain multiple DTBs at nonzero offsets.
        self.firmware.write_bytes((b"xbl-config\0" + b"\0\0".join(dtbs)).ljust(1024, b"\0"))

    def assert_enabled(self, expected, layout):
        image = self.dev.read_bytes()
        self.assertEqual(image, expected)
        self.assertEqual(firmware_key7(image), 1)
        status = self.status()
        self.assertEqual(status["CDT_LAYOUT"], layout)
        self.assertEqual(status["CDT_KEY7"], "1")
        self.assertEqual(status["CDT_BACKUP_STATUS"], "enabled_match")

    def test_existing_native_selftest(self):
        self.assertIn("CDT_SELFTEST=PASS", self.call("selftest"))

    def test_pjz110_empty_v5_matches_captured_image(self):
        """Golden SHA-256 from the OnePlus 13 cold-boot verification image."""
        self.assertEqual(self.status(V5_MTP)["CDT_EMPTY_CAN_ENABLE"], "1")
        self.enable(V5_MTP)
        self.assert_enabled(cdt_image(bytes.fromhex("05 08 01 00 00 00 01 07 01")), "v4_v5_7")
        self.assertEqual(
            hashlib.sha256(self.dev.read_bytes()).hexdigest(),
            "d813b4a15df1207f0488bb7814e169b759fd1d9144b2860ed25d4ef56b5cbfb5",
        )
        self.assertIn("CDT_ACTION=already_enabled", self.enable(V5_MTP))
        self.assertEqual(self.backup.read_bytes(), EMPTY)
        self.restore()
        self.assertEqual(self.dev.read_bytes(), EMPTY)

    def test_xiaomi_2509fpn0bc_matches_issue_3_image(self):
        """PlatformInfo from both DTBs and CDT SHA-256 from the supplied diagnostic ZIP."""
        template = platform_dtb(V6_MTP)
        self.write_firmware(template, template)
        status = self.status(V6_MTP)
        self.assertEqual(status["CDT_EMPTY_CAN_ENABLE"], "1")
        self.assertEqual(status["CDT_LAYOUT"], "v6_10")
        self.assertEqual(status["CDT_LAYOUT_SOURCE"], "active_xbl_config")
        self.assertEqual(status["CDT_LAYOUT_CHECK"], "confirmed")
        self.enable()
        self.assert_enabled(cdt_image(bytes.fromhex("06 08 01 00 00 00 00 00 00 01 07 01")), "v6_10")
        self.assertEqual(
            hashlib.sha256(self.dev.read_bytes()).hexdigest(),
            "82bfc4e098a6790c6b515a505b9d9077653d92579530e3c9543c07a203c310cb",
        )
        self.assertIn("CDT_ACTION=already_enabled", self.enable())
        self.assertEqual(self.backup.read_bytes(), EMPTY)
        self.restore()
        self.assertEqual(self.dev.read_bytes(), EMPTY)

    def test_opd2515_empty_v5_uses_the_qrd_firmware_template(self):
        """Both FDTs in the OPD2515 active xbl_config have this exact PlatformInfo."""
        template = platform_dtb(V5_QRD)
        self.write_firmware(template, template)
        status = self.status(V5_QRD, require_template=True)
        self.assertEqual(status["CDT_LAYOUT_SOURCE"], "active_xbl_config")
        self.assertEqual(status["CDT_LAYOUT_CHECK"], "confirmed")
        self.assertEqual(status["CDT_EMPTY_CAN_ENABLE"], "1")
        self.enable(V5_QRD, require_template=True)
        self.assert_enabled(cdt_image(bytes.fromhex("05 0b 02 00 00 00 01 07 01")), "v4_v5_7")
        self.assertEqual(self.backup.read_bytes(), EMPTY)
        self.restore()
        self.assertEqual(self.dev.read_bytes(), EMPTY)

    def test_opd2515_rejects_an_unconfirmed_v5_template(self):
        for templates, reason in (
            ((), "unavailable"),
            ((V5_MTP,), "no_match"),
            ((V5_QRD, V5_QRD[:5] + b"\x01\x00"), "ambiguous"),
        ):
            with self.subTest(reason=reason):
                self.firmware.unlink(missing_ok=True)
                if templates:
                    self.write_firmware(*(platform_dtb(value) for value in templates))
                status = self.status(V5_QRD, require_template=True)
                self.assertEqual(status["CDT_EMPTY_CAN_ENABLE"], "0")
                self.assertEqual(status["CDT_LAYOUT_CHECK"], reason)
                self.assertIn(f"CDT_ERR=layout_unconfirmed:{reason}",
                              self.enable(V5_QRD, require_template=True, success=False))
                self.assertEqual(self.dev.read_bytes(), EMPTY)
                self.assertFalse(self.backup.exists())

    def test_selects_qrd_when_mtp_template_is_also_present(self):
        """OPD2511 board identity, with a synthetic QRD v6 firmware fixture."""
        self.write_firmware(platform_dtb(V6_MTP), platform_dtb(V6_QRD), platform_dtb(V6_QRD))
        self.assertEqual(self.status(V6_QRD)["CDT_EMPTY_CAN_ENABLE"], "1")
        self.enable(V6_QRD)
        self.assert_enabled(cdt_image(bytes.fromhex("06 0b 02 00 00 00 00 00 00 01 07 01")), "v6_10")

    def test_empty_v6_preserves_firmware_oem_bytes_and_other_keys(self):
        original = bytes.fromhex("06 08 01 00 00 12 34 56 78 01 02 09")
        self.write_firmware(platform_dtb(original))
        self.enable()
        self.assert_enabled(cdt_image(bytes.fromhex("06 08 01 00 00 12 34 56 78 02 02 09 07 01")), "v6_10")

    def test_stock_v6_inserts_key_without_moving_second_block(self):
        original = bytes.fromhex("06 08 01 00 00 12 34 56 78 01 02 09")
        other = bytes.fromhex("ca fe ba be")
        stock = cdt_image(original, second_block=other)
        self.dev.write_bytes(stock)
        self.write_firmware(platform_dtb(V6_MTP))
        self.assertEqual(self.status()["CDT_V6_CAN_ENABLE"], "1")
        self.enable()
        expected = bytes.fromhex("06 08 01 00 00 12 34 56 78 02 02 09 07 01")
        self.assert_enabled(cdt_image(expected, second_block=other), "v6_10")
        self.restore()
        self.assertEqual(self.dev.read_bytes(), stock)

    def test_v2546a_stock_v5_uses_disk_layout_instead_of_v6_default(self):
        stock_v5 = bytes.fromhex("05 0b 01 00 00 00 00")
        stock = bytearray(cdt_image(stock_v5, second_block=b"DDR"))
        stock[-1] = 0x5A
        self.dev.write_bytes(stock)
        self.write_firmware(platform_dtb(V6_MTP))
        inferred_platform = bytes.fromhex("06 0b 01 00 00")
        self.assertEqual(self.status(inferred_platform)["CDT_LAYOUT_CAN_ENABLE"], "1")
        self.enable(inferred_platform)
        expected = bytearray(stock)
        struct.pack_into("<H", expected, 16, 9)
        expected[28:31] = b"\x01\x07\x01"
        self.assert_enabled(bytes(expected), "v4_v5_7")
        self.restore()
        self.assertEqual(self.dev.read_bytes(), stock)

    def test_preexisting_repacked_v5_matches_imported_backup(self):
        stock = cdt_image(V5_MTP, second_block=b"DDR", second_offset=64)
        old_enabled = cdt_image(V5_MTP[:6] + b"\x01\x07\x01", second_block=b"DDR", second_offset=31)
        self.dev.write_bytes(old_enabled)
        self.imported.write_bytes(stock)
        self.restore("--from-file", self.imported)
        self.assertEqual(self.dev.read_bytes(), stock)

    def test_plk110_compact_v6_preserves_oem_and_restores_exact_original(self):
        stock = cdt_image(PLK110_V6_STOCK, second_block=b"DDR", second_offset=34)
        self.dev.write_bytes(stock)
        self.write_firmware(platform_dtb(PLK110_V6_STOCK), platform_dtb(PLK110_V6_STOCK + b"\x00"))
        status = self.status()
        self.assertEqual(status["CDT_DISK"], "stock")
        self.assertEqual(status["CDT_LAYOUT"], "v6_9")
        self.assertEqual(status["CDT_KEY7"], "absent")
        self.assertEqual(status["CDT_V6_CAN_ENABLE"], "1")
        self.enable()
        expected = cdt_image(PLK110_V6_STOCK + b"\x01\x07\x01", second_block=b"DDR", second_offset=34)
        self.assert_enabled(expected, "v6_10")
        self.assertEqual(self.backup.read_bytes(), stock)
        self.restore()
        self.assertEqual(self.dev.read_bytes(), stock)

    def test_compact_v6_needs_three_free_bytes(self):
        self.write_firmware(platform_dtb(V6_MTP))
        for stock in (
            cdt_image(PLK110_V6_STOCK, second_block=b"DDR", second_offset=33),
            cdt_image(PLK110_V6_STOCK)[:29] + b"\x45" + EMPTY[30:],
        ):
            self.dev.write_bytes(stock)
            self.assertEqual(self.status()["CDT_V6_CAN_ENABLE"], "0")
            self.enable(success=False)
            self.assertEqual(self.dev.read_bytes(), stock)
            self.assertFalse(self.backup.exists())

    def test_empty_v6_accepts_compact_firmware_template(self):
        self.write_firmware(platform_dtb(PLK110_V6_STOCK))
        self.enable()
        self.assert_enabled(cdt_image(PLK110_V6_STOCK + b"\x01\x07\x01"), "v6_10")
        self.restore()
        self.assertEqual(self.dev.read_bytes(), EMPTY)

    def test_plk110_can_import_original_to_restore_old_misplaced_key(self):
        stock = cdt_image(PLK110_V6_STOCK)
        self.dev.write_bytes(cdt_image(bytes.fromhex("06 08 01 00 00 00 01 07 01 60 ff")))
        self.imported.write_bytes(stock)
        self.restore("--from-file", self.imported)
        self.assertEqual(self.dev.read_bytes(), stock)

    def test_nine_byte_legacy_key_is_not_rewritten_as_oem_data(self):
        legacy = cdt_image(OLD_V6_MTP)
        self.dev.write_bytes(legacy)
        self.write_firmware(platform_dtb(V6_MTP))
        self.assertEqual(self.status()["CDT_DISK"], "legacy_v6")
        self.assertIn("CDT_ERR=legacy_v6_restore_required", self.enable(success=False))
        self.assertEqual(self.dev.read_bytes(), legacy)

    def test_stock_v6_replaces_disabled_key_when_no_growth_fits(self):
        header = bytes.fromhex("06 08 01 00 00 12 34 56 78 01")
        other = b"DDR"
        self.dev.write_bytes(cdt_image(header + b"\x07\x00", second_block=other, second_offset=34))
        self.write_firmware(platform_dtb(V6_MTP))
        self.assertEqual(self.status()["CDT_KEY7"], "0")
        self.enable()
        self.assert_enabled(cdt_image(header + b"\x07\x01", second_block=other, second_offset=34), "v6_10")

    def test_stock_v6_rejects_insertion_that_overlaps_second_block(self):
        stock = cdt_image(V6_MTP, second_block=b"DDR", second_offset=32)
        self.dev.write_bytes(stock)
        self.write_firmware(platform_dtb(V6_MTP))
        self.assertEqual(self.status()["CDT_V6_CAN_ENABLE"], "0")
        self.enable(success=False)
        self.assertEqual(self.dev.read_bytes(), stock)
        self.assertFalse(self.backup.exists())

    def test_v6_template_requires_room_or_an_existing_key(self):
        keys = bytes(value for key in range(256) if key != 7 for value in (key, 0))
        self.write_firmware(platform_dtb(V6_MTP[:9] + b"\xff" + keys))
        self.enable(success=False)
        self.assertEqual(self.dev.read_bytes(), EMPTY)
        self.assertFalse(self.backup.exists())
        keys = b"\x07\x00" + keys[2:]
        template = V6_MTP[:9] + b"\xff" + keys
        self.write_firmware(platform_dtb(template))
        self.enable()
        self.assert_enabled(cdt_image(template[:11] + b"\x01" + template[12:]), "v6_10")

    def test_legacy_empty_image_can_be_restored_from_saved_backup(self):
        legacy = cdt_image(OLD_V6_MTP)
        with self.assertRaises(ValueError):
            firmware_key7(legacy)
        self.dev.write_bytes(legacy)
        self.backup.write_bytes(EMPTY)
        status = self.status()
        self.assertEqual(status["CDT_DISK"], "legacy_v6")
        self.assertEqual(status["CDT_KEY7"], "absent")
        self.assertEqual(status["CDT_BACKUP_STATUS"], "legacy_enabled_match")
        self.enable(success=False)
        self.assertEqual(self.dev.read_bytes(), legacy)
        self.restore()
        self.assertEqual(self.dev.read_bytes(), EMPTY)

    def test_legacy_nonempty_images_match_imported_original(self):
        original = bytes.fromhex("06 08 01 00 00 12 00 56 78 01 02 09")
        legacy = bytes.fromhex("06 08 01 00 00 12 01 07 01 56 78 01 02 09")
        other = b"DDR"
        stock = cdt_image(original, second_block=other)
        self.imported.write_bytes(stock)
        for offset in (64, 66):
            with self.subTest(second_block_offset=offset):
                self.backup.unlink(missing_ok=True)
                self.dev.write_bytes(cdt_image(legacy, second_block=other, second_offset=offset))
                self.assertEqual(self.status()["CDT_DISK"], "legacy_v6")
                self.restore("--from-file", self.imported)
                self.assertEqual(self.dev.read_bytes(), stock)
                self.assertEqual(self.backup.read_bytes(), stock)

    def test_legacy_restore_checks_entire_partition(self):
        altered = cdt_image(OLD_V6_MTP)[:-1] + b"\x01"
        self.dev.write_bytes(altered)
        self.imported.write_bytes(EMPTY)
        self.assertIn("CDT_ERR=restore_image_mismatch", self.restore("--from-file", self.imported, success=False))
        self.assertEqual(self.dev.read_bytes(), altered)
        self.assertFalse(self.backup.exists())

    def test_qrd_with_only_mtp_firmware_template_cannot_enable(self):
        """V2546A reports QRD rev 1, while its xbl_config contains v6 MTP rev 1."""
        qrd_rev1 = bytes.fromhex("06 0b 01 00 00")
        self.write_firmware(platform_dtb(V6_MTP), platform_dtb(V6_MTP))
        self.assertEqual(self.status(qrd_rev1)["CDT_LAYOUT_CHECK"], "no_match")
        self.assertEqual(self.status(qrd_rev1)["CDT_EMPTY_CAN_ENABLE"], "0")
        self.assertIn("CDT_ERR=layout_unconfirmed:no_match", self.enable(qrd_rev1, success=False))
        self.assertEqual(self.dev.read_bytes(), EMPTY)

    def test_stock_legacy_generations_edit_the_key_count_at_their_own_offset(self):
        for before, after, layout in (
            ("03 08 01 00 00 01 02 09", "03 08 01 00 00 02 02 09 07 01", "v3_6"),
            ("04 08 01 00 00 00 01 02 09", "04 08 01 00 00 00 02 02 09 07 01", "v4_v5_7"),
            ("05 0b 02 00 00 03 01 07 00", "05 0b 02 00 00 03 01 07 01", "v4_v5_7"),
        ):
            with self.subTest(version=before[:2]):
                stock = cdt_image(bytes.fromhex(before))
                self.dev.write_bytes(stock)
                self.backup.unlink(missing_ok=True)
                self.enable(bytes.fromhex(before))
                self.assert_enabled(cdt_image(bytes.fromhex(after)), layout)
                self.restore()
                self.assertEqual(self.dev.read_bytes(), stock)

    def test_unconfirmed_firmware_templates_leave_the_partition_untouched(self):
        cases = (
            ("absent", (), "unavailable"),
            ("different platform version", (platform_dtb(bytes.fromhex("06 0b 01 00 00 00 00 00 00 00")),), "no_match"),
            ("wrong node", (platform_dtb(V6_QRD, node=b"Other"),), "no_match"),
            ("truncated", (platform_dtb(V6_QRD)[:40],), "no_match"),
            ("unknown header length", (platform_dtb(V6_QRD + b"\x00"),), "unsupported"),
            ("conflicting layouts", (platform_dtb(V6_QRD), platform_dtb(V6_QRD[:7])), "ambiguous"),
            ("conflicting templates", (platform_dtb(V6_QRD), platform_dtb(V6_QRD[:5] + b"\x01" + V6_QRD[6:])), "ambiguous"),
        )
        for name, dtbs, reason in cases:
            with self.subTest(case=name):
                self.firmware.unlink(missing_ok=True)
                if dtbs:
                    self.write_firmware(*dtbs)
                status = self.status(V6_QRD)
                self.assertEqual(status["CDT_EMPTY_CAN_ENABLE"], "0")
                self.assertEqual(status["CDT_LAYOUT_CHECK"], reason)
                self.assertIn(f"CDT_ERR=layout_unconfirmed:{reason}", self.enable(V6_QRD, success=False))
                self.assertEqual(self.dev.read_bytes(), EMPTY)
                self.assertFalse(self.backup.exists())

    def test_stock_v6_with_unconfirmed_firmware_is_readable_but_cannot_enable(self):
        stock = cdt_image(V6_MTP)
        self.dev.write_bytes(stock)
        self.write_firmware(platform_dtb(V6_QRD))
        status = self.status()
        self.assertEqual(status["CDT_DISK"], "stock")
        self.assertEqual(status["CDT_LAYOUT"], "v6_10")
        self.assertEqual(status["CDT_V6_CAN_ENABLE"], "0")
        self.assertIn("CDT_ERR=layout_unconfirmed:no_match", self.enable(success=False))
        self.assertEqual(self.dev.read_bytes(), stock)

    def test_partial_v6_write_can_recover_original(self):
        target = cdt_image(bytes.fromhex("06 08 01 00 00 00 00 00 00 01 07 01"))
        partial = target[:25] + EMPTY[25:]
        self.dev.write_bytes(partial)
        journal = self.backup.with_suffix(".bin.transaction")
        journal.write_bytes(EMPTY + target)
        self.assertIn("CDT_RECOVERY=previous_verified", self.call("recover", self.dev, self.backup))
        self.assertEqual(self.dev.read_bytes(), EMPTY)
        self.assertFalse(journal.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
