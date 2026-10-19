import hashlib
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'tools/vr/module'
JAVA_ARTIFACTS = {'tasks-core': 'tasks-vision'}


def libraries():
    return [line.split() for line in (MODULE / 'libraries.txt').read_text().splitlines() if line.strip()]


class VrModuleTest(unittest.TestCase):
    def test_native_libraries_match_the_java_classes_in_the_apk(self):
        gradle = (ROOT / 'app/build.gradle').read_text()
        for coordinate, url, sha256, library in libraries():
            with self.subTest(coordinate=coordinate):
                group, artifact, version = coordinate.split(':')
                self.assertIn(f"'{group}:{JAVA_ARTIFACTS.get(artifact, artifact)}:{version}", gradle)
                self.assertTrue(url.startswith('https://') and url.endswith(f'{artifact}-{version}.aar'))
                self.assertRegex(sha256, '^[0-9a-f]{64}$')
                self.assertRegex(library, r'^lib[a-z0-9_]+\.so$')

    def test_models_match_their_provenance(self):
        models = sorted((MODULE / 'models').glob('*.task'))
        self.assertEqual([model.name for model in models],
                         ['hand_landmarker.task', 'hand_landmarker_lite.task', 'pose_landmarker_lite.task'])
        for model in models:
            with self.subTest(model=model.name):
                provenance = json.loads(model.with_suffix('.provenance.json').read_text())
                self.assertEqual(hashlib.sha256(model.read_bytes()).hexdigest(), provenance['sha256'])

    def test_every_shipped_license_exists(self):
        script = (MODULE / 'build.sh').read_text()
        for source in re.findall(r'cp "\$repo/([^"]+)" "\$root/licenses/', script):
            with self.subTest(source=source):
                self.assertTrue((ROOT / source).is_file())
        for name in re.search(r'for name in ([a-z ]+); do mkdir -p "\$root/licenses', script).group(1).split():
            with self.subTest(name=name):
                self.assertIn(f'$root/licenses/{name}/', script)

    def test_the_workflow_builds_this_module(self):
        workflow = (ROOT / '.github/workflows/build-vr-module.yml').read_text()
        self.assertIn('tools/vr/module/build.sh out', workflow)
        self.assertIn("- 'tools/vr/**'", workflow)
        self.assertTrue(re.search(r'out/vr\.tar\.zst\s+out/vr\.sha256\s+out/vr\.files\.txt', workflow))


if __name__ == '__main__':
    unittest.main()
