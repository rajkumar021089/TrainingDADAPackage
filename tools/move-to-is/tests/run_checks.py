"""Developer checks. Python is not required to run the actual Git Bash launcher.

Run: python tests/run_checks.py --sdk-jars /path/to/Eclipse/platform/jars
The SDK directory supplies real Eclipse API jars, not a Designer installation.
No vendor nature or live IS integration is claimed by these checks.
"""
import argparse
import fcntl
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def run(*args, **kwargs):
    return subprocess.run(args, capture_output=True, text=True, **kwargs)


def snapshot(path):
    return {str(p.relative_to(path)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in path.rglob('*') if p.is_file()}


def fixture_checks():
    with tempfile.TemporaryDirectory(prefix='designer-launch-check-') as tmp:
        base = Path(tmp)
        repos = base / 'git parent' / 'ESB'
        repos.mkdir(parents=True)
        workspace = base / 'designer workspace'
        (workspace / '.metadata').mkdir(parents=True)
        packages = base / 'IS packages'
        packages.mkdir()
        repo = repos / 'NBKAurionProServices'
        repo.mkdir()
        subprocess.run(['git', 'init', '-q', str(repo)], check=True)
        source = repo / repo.name
        source.mkdir()
        for name, content in [('manifest.v3', '<Values/>'), ('.project', '<projectDescription><name>NBKAurionProServices</name></projectDescription>'), ('.classpath', '<classpath/>')]:
            (source / name).write_text(content)
        args = ['bash', str(ROOT / 'ImportAndMoveToIS.sh'), '--repo-root', str(repos),
                '--packages-dir', str(packages), '--workspace', str(workspace)]
        before = snapshot(base)
        result = run(*args, '--package', source.name)
        assert result.returncode == 0, result.stderr
        assert 'DESIGNER PLAN' in result.stdout and str(source) in result.stdout
        assert snapshot(base) == before, 'Dry run modified fixture files'
        print('PASS: complete dry run selects the inner project and changes no files')
        blocker = packages / source.name
        blocker.mkdir()
        (blocker / 'uncommitted.txt').write_text('preserve this')
        before = snapshot(base)
        result = run(*args, '--apply')
        assert result.returncode != 0 and 'existing target folders' in result.stderr
        assert snapshot(base) == before
        print('PASS: existing copied target blocks before mutation and preserves edits')
        shutil.rmtree(blocker)
        (source / '.classpath').unlink()
        result = run(*args)
        assert result.returncode != 0 and 'Missing .project or .classpath' in result.stderr
        assert not list(packages.iterdir())
        print('PASS: missing Designer metadata is rejected before applying')
        (source / '.classpath').write_text('<classpath/>')
        order = base / 'subset.txt'
        order.write_bytes(b'# selected package\r\nNBKAurionProServices\r\n')
        result = run(*args, '--order', str(order))
        assert result.returncode == 0 and 'Projects:  1' in result.stdout, result.stderr
        print('PASS: CRLF package allow-list selects the intended project')
        result = run(*args, '--apply')
        assert result.returncode != 0 and 'Windows Git Bash' in result.stderr
        print('PASS: apply on a non-Windows host stops before mutation')


def api_and_bootstrap_checks(sdk):
    jars = sorted(sdk.glob('*.jar'))
    assert jars, f'No Eclipse API jars in {sdk}'
    cp = os.pathsep.join(str(p) for p in jars)
    with tempfile.TemporaryDirectory(prefix='designer-api-check-') as tmp:
        base = Path(tmp)
        classes = base / 'classes'
        classes.mkdir()
        result = run('java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-source', '11', '-target', '11',
                     '-classpath', cp, '-d', str(classes), str(ROOT / 'importer/nbk/tools/lsdimport/Startup.java'),
                     str(ROOT / 'importer/Bootstrap.java'), str(ROOT / 'tests/MetadataCheck.java'))
        assert result.returncode == 0, result.stdout + result.stderr
        print('PASS: helper and bootstrap compile against real Eclipse API jars')
        result = run('java', '-cp', str(classes) + os.pathsep + cp, 'MetadataCheck')
        assert result.returncode == 0, result.stdout + result.stderr
        print(result.stdout.strip())
        eclipse = base / 'fake-designer'
        plugins = eclipse / 'plugins'
        plugins.mkdir(parents=True)
        for jar in jars:
            shutil.copyfile(jar, plugins / jar.name)
        # Metadata-only marker bundles allow checking packaging. They do not
        # simulate the proprietary LSD nature or EGit runtime operations.
        for bundle in ('org.eclipse.egit.core', 'com.example.vcsintegration'):
            with zipfile.ZipFile(plugins / (bundle + '_test.jar'), 'w') as out:
                out.writestr('META-INF/MANIFEST.MF', f'Manifest-Version: 1.0\nBundle-SymbolicName: {bundle}\n\n')
        workspace = base / 'workspace'
        (workspace / '.metadata').mkdir(parents=True)
        run_dir = base / 'run'
        run_dir.mkdir()
        source = base / 'NBKBootstrapFixture'
        source.mkdir()
        (source / 'manifest.v3').write_text('<Values/>')
        (source / '.project').write_text('<projectDescription><name>NBKBootstrapFixture</name></projectDescription>')
        (source / '.classpath').write_text('<classpath/>')
        (run_dir / 'import.properties').write_text('format=1\nworkspace=' + str(workspace) + '\ncount=1\npackage.0.name=NBKBootstrapFixture\npackage.0.path=' + str(source) + '\n')
        arguments = ['java', str(ROOT / 'importer/Bootstrap.java'), str(eclipse), str(ROOT / 'importer'), str(run_dir), str(workspace)]
        lock_file = workspace / '.metadata/.lock'
        with lock_file.open('w') as stream:
            fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = run(*arguments)
            assert result.returncode != 0 and 'Close it first' in result.stderr, result.stdout + result.stderr
            assert not (eclipse / 'dropins').exists()
        print('PASS: workspace lock stops bootstrap before installing a helper')
        (source / '.project').write_text('<projectDescription><name>WrongPackage</name></projectDescription>')
        result = run(*arguments)
        assert result.returncode != 0 and '.project name differ' in result.stderr
        assert not (eclipse / 'dropins').exists()
        print('PASS: mismatched project metadata is rejected before installation or IS changes')
        (source / '.project').write_text('<projectDescription><name>NBKBootstrapFixture</name></projectDescription>')
        result = run(*arguments)
        assert result.returncode == 0, result.stdout + result.stderr
        built = eclipse / 'dropins/nbk.tools.lsdimport_1.0.0.jar'
        with zipfile.ZipFile(built) as jar:
            manifest = jar.read('META-INF/MANIFEST.MF').decode()
            assert 'org.eclipse.ui.startup' in jar.read('plugin.xml').decode()
            code = jar.read('nbk/tools/lsdimport/Startup.class')
            assert int.from_bytes(code[6:8], 'big') == 55, 'Expected Java 11 class format'
            assert 'nbk.tools.lsdimport;singleton:=true' in manifest
        print('PASS: bootstrap builds an Eclipse startup bundle with Java 11 class format')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk-jars', type=Path, required=True)
    args = parser.parse_args()
    for name in ('MoveToIS.sh', 'ImportAndMoveToIS.sh'):
        subprocess.run(['bash', '-n', str(ROOT / name)], check=True)
    fixture_checks()
    api_and_bootstrap_checks(args.sdk_jars.resolve())
    print('NOT RUN: proprietary Designer LSD nature, Windows junctions, live Integration Server')
