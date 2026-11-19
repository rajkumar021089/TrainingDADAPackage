# Import NBK projects into Designer and link/load them in local IS

Run **`ImportAndMoveToIS.sh`** for the complete workflow. `MoveToIS.sh` remains available for the filesystem/IS steps alone.

The complete launcher:

1. Finds inner NBK package projects in the cloned repositories and validates their manifests, Git working trees, and target folders.
2. Checks that the selected Designer workspace is closed, then builds a small Eclipse startup helper against the jars already installed with your Designer.
3. Creates Windows junctions and requests package activation, enable, and reload on the local IS.
4. Starts Designer with your existing workspace. The helper uses Eclipse APIs to import each project in place, configure the installed LSD and Java natures/builders, and connect it to its existing Git repository through EGit.
5. Verifies the project location, LSD configuration, and Git directory association, then writes a result and metadata backups.

**This version includes the Designer import and Git association that the earlier copy/link-only script did not automate.** It does not copy package contents, copy `.git`, or manually write Eclipse workspace `.metadata` files. `.project` changes are made through Eclipse.

## Expected layout

| Location | Purpose |
| --- | --- |
| `~/git/ESB/NBKAurionProServices/.git` | Existing repository metadata |
| `~/git/ESB/NBKAurionProServices/NBKAurionProServices/` | Inner package project, containing `manifest.v3`, `.project`, and `.classpath` |
| `C:/SoftwareAG1015_LCL/IntegrationServer/instances/default/packages/NBKAurionProServices` | Junction pointing to the inner project |

The `.project` name must equal the inner package directory name. Existing classpath contents are preserved. If a checkout lacks `.project` or `.classpath`, the launcher stops instead of inventing a Designer project/classpath configuration.

## Prerequisites

- Windows Git Bash and Git. No Python, PowerShell, Maven, or separate Eclipse installation is required on your machine.
- The entire `tools/move-to-is` directory from this repository, including the `importer` sources.
- **Close Designer first.** Use your actual existing workspace directory, not the Git checkout or the IS packages directory. The script checks its lock; it never kills Designer or deletes a lock.
- Designer with **Local Version Control Integration** and **EGit** installed, plus a compatible **JDK** containing the Java compiler. The launcher first checks Designer's `eclipse.ini` runtime, then the installation's `jvm/jvm/bin/java.exe`; use `--java` to specify another matching JDK.
- The same local development IS already configured in that Designer workspace. For the on-premises LSD workflow, Designer and IS must be in the matching installation. The vendor-documented `watt.server.ns.lockingMode=system` prerequisite still applies.
- In Designer's Local Service Development preferences, use **Move project to Integration Server package as linked resource**. This is an installation/workspace prerequisite; the script does not guess private vendor preference keys or configure server credentials.
- Start the local IS before an ordinary `--apply` run. The default URL is `http://localhost:4455`; the password is requested interactively and is not put in the import plan or Java command line.
- Permission to create directory junctions and write to Designer's `dropins` directory. Designer must process drop-in bundles and allow the new helper under its Startup and Shutdown preferences.

## Run one package first

From your cloned `TrainingDADAPackage` repository, preview the complete operation:

```bash
bash tools/move-to-is/ImportAndMoveToIS.sh \
  --repo-root "$HOME/git/ESB" \
  --workspace "/c/Users/YOUR_USER/YOUR_DESIGNER_WORKSPACE" \
  --designer "/c/SoftwareAG1015_LCL/Designer/eclipse/eclipse.exe" \
  --package NBKAurionProServices
```

Replace the workspace placeholder with the directory you normally select when opening Designer. The preview changes no files or IS state. Once its paths are correct, close Designer and run:

```bash
bash tools/move-to-is/ImportAndMoveToIS.sh \
  --repo-root "$HOME/git/ESB" \
  --workspace "/c/Users/YOUR_USER/YOUR_DESIGNER_WORKSPACE" \
  --designer "/c/SoftwareAG1015_LCL/Designer/eclipse/eclipse.exe" \
  --package NBKAurionProServices \
  --apply
```

Designer opens as part of the operation. The launcher waits for the import result and leaves Designer open. Do not open another instance while it is running. The helper is idle on later ordinary launches because they have no `-Dnbk.lsdimport.plan` argument.

To process all detected packages, omit `--package`. To use a specific subset and dependency load order, use `--order package-order.txt`; only the package names in that file are processed. The IS loading phase follows that order. Workspace import order has no dependency semantics.

Other options:

| Option | Behavior |
| --- | --- |
| `--java /c/path/to/jdk/bin/java.exe` | Use this matching JDK for compilation and launching Designer |
| `--packages-dir DIR` | Use a different local IS packages directory |
| `--url http://localhost:PORT` | Use a different local IS port |
| `--user USER` | Use a different IS account |
| `--build-java` | Run the instance's `jcode.bat make` before loading packages |
| `--no-load` | Explicitly skip live IS loading; create junctions and import projects only |
| `--timeout 1800` | Allow more startup/import time for a large batch |

## Existing copied folders

A real folder created by the old copy script blocks the new junction. The launcher stops before applying the run when it detects that conflict.

Review changes in both copies first. With local IS stopped, preserve the copied folder **outside the IS `packages` directory**, then start IS and rerun the preview. Do not place a second package backup in `packages`, where IS can discover it. The scripts never rename, remove, replace, or merge these folders automatically.

From Windows CMD, junctions can be inspected with:

```bat
dir /AL "C:\SoftwareAG1015_LCL\IntegrationServer\instances\default\packages"
```

## Results and verification

The launcher prints a `DesignerImport-logs-*` directory beneath the repository parent. It contains `bootstrap.log`, `designer-launch.log`, `import.properties`, `result.properties`, `import.log`, and backups of each package's `.project` and `.classpath`. The IS helper creates its response/build logs inside that same run directory.

`status=SUCCESS` in `result.properties` means the Eclipse project imports, LSD nature/builder checks, and EGit directory associations succeeded. The shell exits with an error if the helper reports a failure or never starts. It does not treat the GUI launcher's PID as proof of success.

IS calls are a separate phase. The internal IS services can return an application error with HTTP 200. Common error forms are detected and all responses are retained; review package loaded/enabled status and dependency/Java errors in IS. If Package Navigator was already connected during startup, refresh its local server node once to obtain the current package list.

Save a harmless service edit in Designer and verify that the original checkout shows it:

```bash
git -C "$HOME/git/ESB/NBKAurionProServices" status --short
```

Git Staging in Designer should show the same changes. Review the expected `.project` updates before committing. The launcher does not commit or push any of your package repositories.

## Preservation and failure behavior

- Projects already registered at their intended source location are reused. A project of the same name at another location, or shared with another VCS/repository, stops the import.
- Existing Java classpaths and unrelated project natures/builders are preserved. Known LSD nature/builder identifiers are replaced with the ones actually registered by this Designer, including the namespace change documented for 12.1.
- Build Automatically is suspended during import and restored afterward. Normal Designer builds may then run if that preference was enabled.
- The small `nbk.tools.lsdimport_1.0.0.jar` remains in Designer's `dropins` directory. To uninstall it, close Designer, remove only that jar, and start Designer with `-clean`. A replaced previous helper jar is saved in the run directory.
- The operation is **not an all-or-nothing transaction**. If IS loading or a later import fails, already created junctions/imported projects are preserved and reported. Metadata backups and normal Git diffs support review and recovery; there is no destructive automatic rollback.

## Validation and compatibility limits

The Java helper has been compiled against real Eclipse platform APIs. Launcher fixture checks cover selection of inner projects, existing-target protection, subset handling, missing metadata, and dry-run preservation. Bootstrap checks exercise jar packaging and workspace lock rejection. Metadata checks cover replacing the old LSD identifiers while preserving unrelated builders and their arguments.

**The proprietary Designer LSD nature, Windows junction creation, and live IS behavior have not been integration-tested on your installation.** This is custom source-built automation, not a vendor-supported headless Designer command. Test one package with the supplied `--package` option before running the full batch.

The developer checks run on a POSIX host with Python, a JDK, and Eclipse API jars using `python3 tests/run_checks.py --sdk-jars /path/to/api-jars`. The compilation check used Eclipse resources 3.18.100, runtime 3.26.100, equinox.common 3.17.0, core.jobs 3.13.200, equinox.registry 3.11.200, osgi 3.18.200, ui.workbench 3.127.0, and team.core 3.9.700. These test dependencies are not downloaded or installed by the user launcher.

## References

- [IBM Designer 10.15 Local Service Development](https://www.ibm.com/docs/en/webmethods-integration/wm-designer/10.15.0?topic=help-using-local-service-development-feature)
- [IBM Designer 12.1 Local Service Development and changed project identifiers](https://www.ibm.com/docs/en/webmethods-integration/wm-designer/12.1.0?topic=help-using-local-service-development-feature)
- [Software AG author's junction/activation automation example](https://community.ibm.com/community/user/viewdocument/how-to-automate-package-import-to-a?CommunityKey=82b75916-ed06-4a13-8eb6-0190da9f1bfa&hlmlt=VT&tab=librarydocuments)
- [Eclipse startup extension](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/extension-points/org_eclipse_ui_startup.html)
- [EGit 5.11 ConnectProviderOperation](https://github.com/eclipse-egit/egit/blob/stable-5.11/org.eclipse.egit.core/src/org/eclipse/egit/core/op/ConnectProviderOperation.java)
