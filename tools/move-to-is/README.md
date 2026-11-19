# Reproduce the linked-resource part of Designer Move Project to IS Package

Prepared for the nested checkout layout from your screenshots:

| Item | Example location |
| --- | --- |
| Repository root and Git metadata | `~/git/ESB/NBKAurionProServices/.git` |
| Actual IS package and Eclipse project | `~/git/ESB/NBKAurionProServices/NBKAurionProServices/` |
| Package manifest | `~/git/ESB/NBKAurionProServices/NBKAurionProServices/manifest.v3` |
| Local IS package entry | `C:\SoftwareAG1015_LCL\IntegrationServer\instances\default\packages\NBKAurionProServices` |

The last entry must be a directory junction to the **inner** package for this linked-resource workflow. IS writes through that junction to the original checkout. Git metadata stays at the outer repository root; do not copy or generate a second `.git` directory inside the package. Git CLI can find the parent repository from the inner package. Designer also needs its Eclipse/EGit project association.

Your screenshot showed a plain `cp -R` followed by `wm.server.packages:packageReload`. A separate copy breaks the relationship to the original working tree. The earlier HTTP response contained `Unknown package` and code `-4`; a reload request alone did not introduce that new package to this server.

## What the script does

- Finds inner `NBK*` folders immediately below each repository, requiring `manifest.v3` directly inside the folder.
- Validates that each package belongs to a Git working tree and rejects duplicate package names.
- Defaults to a read-only plan. `--apply` creates junctions with Windows `mklink /J`.
- Refuses existing target folders that do not already resolve to the intended source. It does not delete, rename, overwrite, or merge them.
- Optionally runs instance `bin/jcode.bat make PACKAGE` with `--build-java`.
- Optionally requests `packageActivate`, `packageEnable`, and `packageReload` with `--online`, prompting once for the IS password.
- Saves build output and complete HTTP responses in a private `MoveToIS-logs-*` folder. It detects common HTTP-200 application errors but does not claim that an arbitrary response proves a package was successfully loaded.

This script **does not register projects in the Designer workspace, add Local Service Development project configuration, or perform Team > Share Project**. Those are Eclipse/Designer operations. A filesystem script cannot substitute for those by copying `.project`, `.git`, or workspace `.metadata`. Full automation of those operations would require an importer running through the Eclipse/Designer APIs; that importer is not included here.

## First, resolve the folders copied by the old script

If a target already exists as a real copied folder, the new script blocks before changing anything. Check for uncommitted changes in both locations first. With the local development IS stopped, preserve the copied folder in a backup location **outside** the IS `packages` directory, then rerun the plan. Avoid a backup inside `packages`, where IS may discover it as another package.

For inspection from Windows CMD:

```bat
dir /AL "C:\SoftwareAG1015_LCL\IntegrationServer\instances\default\packages"
```

For a single package, the corresponding link command is:

```bat
mklink /J "C:\SoftwareAG1015_LCL\IntegrationServer\instances\default\packages\NBKAurionProServices" "C:\Users\YOUR_USER\git\ESB\NBKAurionProServices\NBKAurionProServices"
```

Replace `YOUR_USER` with the actual Windows profile name. The target junction path must not already exist.

## Run from Git Bash

Place `MoveToIS.sh` in `~/git/ESB`, or use `--repo-root` with that directory. The defaults match the IS path and port in your earlier screenshot.

```bash
cd ~/git/ESB
bash MoveToIS.sh --package NBKAurionProServices
bash MoveToIS.sh --package NBKAurionProServices --apply --online
```

For all detected packages, omit `--package`:

```bash
bash MoveToIS.sh
bash MoveToIS.sh --apply --online
```

For packages with Java services that require compilation, add `--build-java`. Review the jcode log; an exit code alone may not show every compiler issue. The build writes generated files into the original checkout through the junction.

If package dependencies require a particular load order, create a plain text file listing the needed packages with dependencies first. `--order` is an allow-list: only names in that file are processed, in that order. Dependency order is not computed from manifests.

```bash
bash MoveToIS.sh --order package-order.txt
bash MoveToIS.sh --order package-order.txt --apply --online --build-java
```

If IS is stopped, use `--apply` without `--online`, then start IS to discover the packages. The script never starts or stops IS.

## Complete the Designer project setup

1. Use Designer's local development server configuration for this same local IS. In the on-premises 10.15 workflow, Designer and the local development IS are in the same installation. An ordinary remote server connection alone does not establish LSD.
2. In **Window > Preferences > Software AG > Service Development > Local Service Development**, select **Move project to Integration Server package as linked resource**.
3. Bulk-import the existing **inner** package projects at their Git checkout locations. Their `.project` and `manifest.v3` should be at the project root. Do not copy them into the workspace. If Designer already knows the project, use the existing project.
4. For projects missing the LSD configuration, use **Configure > Convert to Local Service Development Project**.
5. If a project is not connected to Git, use **Team > Share Project > Git** and select its existing outer repository. This associates the project with the existing working tree; it should not create a new repository or relocate the project.
6. Once the script's links and IS loading are complete, refresh Project Explorer and the local server in Package Navigator. Use **Load IS Package** where needed.

Recent Designer versions also support selecting multiple projects and using **Move Project to IS Package** directly. That is a supported bulk alternative to the filesystem/IS part of this script.

If these are older projects imported into Designer 12.1, IBM says to reapply the LSD project configuration. Its 12.1 documentation identifies builder `com.webmethods.sdev.vcsintegration.ISPackageBuilder` and nature `com.webmethods.sdev.vcsintegration.nature`. Do not blindly copy 10.15 project metadata over a 12.1 project; convert with the installed Designer version.

## Verify one package before processing the remaining repositories

1. Check that `dir /AL` shows the IS junction pointing to the **inner** package folder.
2. Check that the package is loaded and enabled in IS Admin, and review IS package errors and the saved HTTP responses.
3. In Designer, edit and save a harmless service change in that package.
4. In Git Bash, check the original checkout:

```bash
git -C ~/git/ESB/NBKAurionProServices status --short
```

The service files should appear as changed there. Designer's Git Staging view should show those same changes after refresh. This verifies the filesystem relationship and the Designer Git association. No commit or push is performed by the script.

## Compatibility and validation

This is an independently prepared replacement script, based on the documented linked-resource workflow and Software AG's example sequence. It is not an exported Designer automation plugin. It requires Windows Git Bash, Git, `cmd.exe`, `cygpath`, and (for online loading) curl. It needs permission to create directory junctions and an IS account authorized for the package administration services.

`wm.server.packages:packageActivate`, `packageEnable`, and `packageReload` are internal services used by the vendor example. Their availability and contracts must be checked on the installed IS version. Do not expose them or broaden their ACLs just to run this script. HTTP 200 can contain an IS application error; retain and review the response body and verify package status.

Shell syntax and local fixture checks were run in the preparation environment. **Windows junction creation, jcode, Designer registration, and live IS calls have not been tested on your machine.**

## References

- [IBM Designer 10.15 Local Service Development](https://www.ibm.com/docs/en/webmethods-integration/wm-designer/10.15.0?topic=help-using-local-service-development-feature)
- [Software AG author's explanation of automating package import](https://community.ibm.com/community/user/viewdocument/how-to-automate-package-import-to-a?CommunityKey=82b75916-ed06-4a13-8eb6-0190da9f1bfa&hlmlt=VT&tab=librarydocuments)
- [Vendor example ANT script](https://github.com/ibm-wm-transition/webmethods-integration-examples/blob/master/localdev_tools_importPackages2IS/import_IS_packages.xml)
- [IBM Designer 12.1 Local Service Development](https://www.ibm.com/docs/en/webmethods-integration/wm-designer/12.1.0?topic=help-using-local-service-development-feature)
