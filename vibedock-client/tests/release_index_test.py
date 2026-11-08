"""The client release files: names, hashes and the index a server reads (scripts/release-index.py)."""
import importlib.util, json, os, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("release_index", os.path.join(HERE, "..", "scripts", "release-index.py"))
release_index = importlib.util.module_from_spec(spec); spec.loader.exec_module(release_index)


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f: f.write(data)


def fake_build(root, board, version="1.2.0", corrupt=False):
    d = os.path.join(root, board)
    write(os.path.join(d, "flash.bin"), b"flash-" + board.encode())
    write(os.path.join(d, "firmware.bin"), b"app-" + board.encode())
    manifest = {"schema": 1, "id": board, "label": board.upper(), "chip": "ESP32-S3", "version": version, "hardware": "tested",
                "firmware": {"sha256": release_index.sha256(os.path.join(d, "firmware.bin")),
                             "flashSha256": "0" * 64 if corrupt else release_index.sha256(os.path.join(d, "flash.bin"))}}
    write(os.path.join(d, "manifest.json"), json.dumps(manifest).encode())


class ReleaseIndexTest(unittest.TestCase):
    def test_the_protocol_comes_from_the_heading_of_the_protocol_document(self):
        self.assertEqual(2, release_index.protocol_version())

    def test_every_board_gets_its_three_files_and_an_index_entry(self):
        with tempfile.TemporaryDirectory() as tmp:
            build, out = os.path.join(tmp, "b"), os.path.join(tmp, "o")
            fake_build(build, "vibedock-m1"); fake_build(build, "esp32-cyd")
            index = release_index.build(build, out, "1.2.0")
            self.assertEqual({"schema": 1, "client": "1.2.0", "protocol": 2}, {k: index[k] for k in ("schema", "client", "protocol")})
            self.assertEqual(["esp32-cyd", "vibedock-m1"], [b["id"] for b in index["boards"]])
            cyd = index["boards"][0]
            self.assertEqual("vibedock-esp32-cyd-1.2.0.bin", cyd["flash"]["file"])
            self.assertEqual("vibedock-esp32-cyd-1.2.0.app.bin", cyd["app"]["file"])
            self.assertEqual("vibedock-esp32-cyd-1.2.0.manifest.json", cyd["manifest"]["file"])
            self.assertEqual("vibedock-vibedock-m1-1.2.0.bin".replace("vibedock-vibedock", "vibedock"), index["boards"][1]["flash"]["file"])
            for board in index["boards"]:
                for kind in ("flash", "app", "manifest"):
                    path = os.path.join(out, board[kind]["file"])
                    self.assertEqual(board[kind]["sha256"], release_index.sha256(path))
                    self.assertEqual(board[kind]["size"], os.path.getsize(path))
            with open(os.path.join(out, "index.json"), encoding="utf-8") as f: self.assertEqual(index, json.load(f))

    def test_a_hash_that_differs_from_the_manifest_stops_the_release(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake_build(os.path.join(tmp, "b"), "esp32-cyd", corrupt=True)
            with self.assertRaisesRegex(ValueError, "does not match the manifest"): release_index.build(os.path.join(tmp, "b"), os.path.join(tmp, "o"), "1.2.0")

    def test_a_manifest_of_another_version_stops_the_release(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake_build(os.path.join(tmp, "b"), "esp32-cyd", version="1.1.0")
            with self.assertRaisesRegex(ValueError, "not 1.2.0"): release_index.build(os.path.join(tmp, "b"), os.path.join(tmp, "o"), "1.2.0")

    def test_no_boards_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, "no board"): release_index.build(tmp, os.path.join(tmp, "o"), "1.2.0")


if __name__ == "__main__":
    unittest.main()
