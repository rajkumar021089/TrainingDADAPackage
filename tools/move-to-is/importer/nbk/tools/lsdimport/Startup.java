package nbk.tools.lsdimport;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IProjectNatureDescriptor;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.team.core.RepositoryProvider;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.progress.UIJob;
import org.osgi.framework.Bundle;

/** Runs inside the normal Designer workbench, through Eclipse and EGit APIs. */
public final class Startup implements IStartup {
    private static final String ID = "nbk.tools.lsdimport";
    private static final String JAVA_NATURE = "org.eclipse.jdt.core.javanature";
    private static final String JAVA_BUILDER = "org.eclipse.jdt.core.javabuilder";
    private static final String GIT_PROVIDER = "org.eclipse.egit.core.GitProvider";

    @Override
    public void earlyStartup() {
        String plan = System.getProperty("nbk.lsdimport.plan");
        if (plan == null || plan.isBlank()) return;
        UIJob job = new UIJob("Import packages as Local Service Development projects") {
            @Override
            public IStatus runInUIThread(IProgressMonitor monitor) {
                return execute(Path.of(plan), monitor);
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private static IStatus execute(Path planFile, IProgressMonitor monitor) {
        Path run = planFile.toAbsolutePath().getParent();
        Path result = run.resolve("result.properties");
        // A consumed plan must not be applied again or overwrite its prior outcome.
        if (Files.exists(result)) return Status.OK_STATUS;
        int imported = 0;
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        Boolean wasAutoBuilding = null;
        Properties outcome = new Properties();
        outcome.setProperty("status", "FAILED");
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(run.resolve("import.log"), StandardCharsets.UTF_8))) {
            try {
                Properties plan = new Properties();
                try (java.io.Reader in = Files.newBufferedReader(planFile, StandardCharsets.UTF_8)) { plan.load(in); }
                if (!"1".equals(plan.getProperty("format"))) throw new IllegalArgumentException("Unsupported import plan format");
                Path expectedWorkspace = Path.of(required(plan, "workspace")).toRealPath();
                if (!Files.isSameFile(expectedWorkspace, workspace.getRoot().getLocation().toFile().toPath())) {
                    throw new IllegalStateException("Designer opened a different workspace; no project was imported");
                }
                result = Path.of(required(plan, "result")).toAbsolutePath().normalize();
                if (!result.getParent().equals(run.normalize())) throw new IllegalArgumentException("Result must stay in the run directory");
                String nature = findLsdNature(workspace);
                String builder = findLsdBuilder(nature);
                log.println("Installed LSD nature: " + nature);
                log.println("Installed LSD builder: " + builder);
                Bundle egit = Platform.getBundle("org.eclipse.egit.core");
                if (egit == null) throw new IllegalStateException("EGit is not installed in this Designer");
                egit.start(Bundle.START_TRANSIENT);
                List<PackageProject> projects = readAndValidate(plan, workspace, nature, builder, egit);
                monitor.beginTask("Importing local package projects", projects.size());
                wasAutoBuilding = workspace.getDescription().isAutoBuilding();
                setAutoBuilding(workspace, false);
                for (PackageProject item : projects) {
                    if (monitor.isCanceled()) throw new OperationCanceledException();
                    monitor.subTask(item.name);
                    backupMetadata(item, run.resolve("metadata-backup").resolve(item.name));
                    IProject project = workspace.getRoot().getProject(item.name);
                    boolean created = !project.exists();
                    if (created) {
                        item.description.setLocationURI(item.source.toUri());
                        project.create(item.description, monitor);
                    }
                    if (!project.isOpen()) project.open(monitor);
                    // Closed projects can acquire their Team provider only after opening.
                    // Check it again before changing their persistent project description.
                    if (RepositoryProvider.getProvider(project) != null) {
                        checkGitMapping(project, item.gitDirectory, egit);
                    }
                    IProjectDescription description = project.getDescription();
                    configure(description, nature, builder);
                    project.setDescription(description, monitor);
                    project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                    RepositoryProvider provider = RepositoryProvider.getProvider(project);
                    if (provider == null) {
                        Class<?> operationClass = egit.loadClass("org.eclipse.egit.core.op.ConnectProviderOperation");
                        Object operation = operationClass.getConstructor(IProject.class, File.class)
                                .newInstance(project, item.gitDirectory.toFile());
                        invoke(operationClass.getMethod("execute", IProgressMonitor.class), operation, monitor);
                    }
                    checkGitMapping(project, item.gitDirectory, egit);
                    addToGitRepositories(egit, item.gitDirectory);
                    project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                    if (!project.hasNature(nature) || !project.hasNature(JAVA_NATURE)) {
                        throw new IllegalStateException("Nature verification failed for " + item.name);
                    }
                    boolean hasBuilder = Arrays.stream(project.getDescription().getBuildSpec())
                            .anyMatch(command -> builder.equals(command.getBuilderName()));
                    if (!hasBuilder) throw new IllegalStateException("LSD builder verification failed for " + item.name);
                    imported++;
                    log.println((created ? "IMPORTED " : "REUSED ") + item.name + " | " + item.source
                            + " | Git " + item.gitDirectory + " | LSD and Git mapping verified");
                    log.flush();
                    monitor.worked(1);
                }
                workspace.save(true, monitor);
                outcome.setProperty("status", "SUCCESS");
                outcome.setProperty("lsd.nature", nature);
                outcome.setProperty("lsd.builder", builder);
            } catch (Exception error) {
                Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
                cause.printStackTrace(log);
                outcome.setProperty("error", String.valueOf(cause.getMessage()));
                if (cause instanceof OperationCanceledException) outcome.setProperty("status", "CANCELLED");
            } finally {
                if (wasAutoBuilding != null) {
                    try { setAutoBuilding(workspace, wasAutoBuilding); }
                    catch (Exception error) {
                        error.printStackTrace(log);
                        outcome.setProperty("status", "FAILED");
                        outcome.setProperty("error", "Could not restore Build Automatically: " + error.getMessage());
                    }
                }
                outcome.setProperty("projects.completed", Integer.toString(imported));
                monitor.done();
                writeResult(result, outcome);
            }
        } catch (Exception error) {
            error.printStackTrace(System.err);
            return new Status(IStatus.ERROR, ID, "Import logging/result failed: " + error.getMessage(), error);
        }
        return "SUCCESS".equals(outcome.getProperty("status")) ? Status.OK_STATUS
                : new Status(IStatus.ERROR, ID, "Package project import failed. Read " + run.resolve("import.log"));
    }

    private static List<PackageProject> readAndValidate(Properties plan, IWorkspace workspace,
            String nature, String builder, Bundle egit) throws Exception {
        int count = Integer.parseInt(required(plan, "count"));
        if (count < 1 || count > 10000) throw new IllegalArgumentException("Invalid package count");
        List<PackageProject> projects = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            String prefix = "package." + i + ".";
            String name = required(plan, prefix + "name");
            if (!name.matches("NBK[A-Za-z0-9_.-]*") || !names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Invalid/duplicate package name: " + name);
            }
            Path source = Path.of(required(plan, prefix + "path")).toRealPath();
            Path git = Path.of(required(plan, prefix + "gitdir")).toRealPath();
            if (!Files.isRegularFile(source.resolve("manifest.v3"))
                    || !Files.isRegularFile(source.resolve(".project"))
                    || !Files.isRegularFile(source.resolve(".classpath")) || !Files.isDirectory(git)) {
                throw new IllegalStateException("Missing package/project/Git metadata: " + source);
            }
            IProjectDescription description = workspace.loadProjectDescription(org.eclipse.core.runtime.Path.fromOSString(source.resolve(".project").toString()));
            if (!name.equals(description.getName())) {
                throw new IllegalStateException("Inner directory " + name + " has .project name " + description.getName());
            }
            configure(description, nature, builder);
            IStatus natures = workspace.validateNatureSet(description.getNatureIds());
            if (!natures.isOK()) throw new IllegalStateException("Invalid project natures for " + name + ": " + natures);
            IProject existing = workspace.getRoot().getProject(name);
            if (existing.exists()) {
                if (!Files.isSameFile(existing.getLocation().toFile().toPath(), source)) {
                    throw new IllegalStateException("Workspace project already points elsewhere: " + name);
                }
                RepositoryProvider provider = RepositoryProvider.getProvider(existing);
                if (provider != null) checkGitMapping(existing, git, egit);
            } else {
                IStatus location = workspace.validateProjectLocationURI(existing, source.toUri());
                if (!location.isOK()) throw new IllegalStateException("Invalid project location for " + name + ": " + location);
            }
            projects.add(new PackageProject(name, source, git, description));
        }
        return projects;
    }

    private static String findLsdNature(IWorkspace workspace) {
        List<String> found = new ArrayList<>();
        for (IProjectNatureDescriptor descriptor : workspace.getNatureDescriptors()) {
            String id = descriptor.getNatureId();
            if (isLsdNature(id)) found.add(id);
        }
        if (found.size() != 1) throw new IllegalStateException("Expected one installed LSD nature; found " + found
                + ". Check Local Version Control Integration installation.");
        return found.get(0);
    }

    private static String findLsdBuilder(String nature) {
        String expected = nature.substring(0, nature.lastIndexOf('.') + 1) + "ISPackageBuilder";
        IExtensionPoint point = Platform.getExtensionRegistry().getExtensionPoint("org.eclipse.core.resources.builders");
        List<String> found = new ArrayList<>();
        for (IExtension extension : point.getExtensions()) {
            String id = extension.getUniqueIdentifier();
            if (expected.equals(id)) return id;
            if (isLsdBuilder(id)) found.add(id);
        }
        if (found.size() != 1) throw new IllegalStateException("Expected an installed ISPackageBuilder; found " + found);
        return found.get(0);
    }

    private static boolean isLsdNature(String id) {
        return id != null && id.toLowerCase(Locale.ROOT).contains("vcsintegration") && id.endsWith(".nature");
    }

    private static boolean isLsdBuilder(String id) {
        return id != null && id.toLowerCase(Locale.ROOT).contains("vcsintegration") && id.endsWith(".ISPackageBuilder");
    }

    private static void configure(IProjectDescription description, String nature, String builder) {
        Set<String> natures = new LinkedHashSet<>();
        for (String existing : description.getNatureIds()) if (!isLsdNature(existing)) natures.add(existing);
        natures.add(JAVA_NATURE); natures.add(nature);
        description.setNatureIds(natures.toArray(new String[0]));
        List<ICommand> commands = new ArrayList<>();
        boolean javaBuilder = false;
        boolean lsdBuilder = false;
        for (ICommand command : description.getBuildSpec()) {
            String existing = command.getBuilderName();
            if (isLsdBuilder(existing)) {
                if (!builder.equals(existing) || lsdBuilder) continue;
                lsdBuilder = true;
            }
            if (JAVA_BUILDER.equals(existing)) javaBuilder = true;
            commands.add(command);
        }
        if (!javaBuilder) {
            ICommand command = description.newCommand(); command.setBuilderName(JAVA_BUILDER); commands.add(command);
        }
        if (!lsdBuilder) {
            ICommand command = description.newCommand(); command.setBuilderName(builder); commands.add(command);
        }
        description.setBuildSpec(commands.toArray(new ICommand[0]));
    }

    private static void checkGitMapping(IProject project, Path expected, Bundle egit) throws Exception {
        RepositoryProvider provider = RepositoryProvider.getProvider(project);
        if (provider == null || !GIT_PROVIDER.equals(provider.getID())) {
            throw new IllegalStateException("Project is not shared with EGit: " + project.getName());
        }
        Class<?> mappingClass = egit.loadClass("org.eclipse.egit.core.project.RepositoryMapping");
        Object mapping = invoke(mappingClass.getMethod("getMapping", IProject.class), null, project);
        if (mapping == null) throw new IllegalStateException("No EGit working-tree mapping: " + project.getName());
        IPath actual = (IPath) invoke(mappingClass.getMethod("getGitDirAbsolutePath"), mapping);
        if (actual == null || !Files.isSameFile(actual.toFile().toPath(), expected)) {
            throw new IllegalStateException("EGit points to a different repository for " + project.getName());
        }
    }

    private static void addToGitRepositories(Bundle egit, Path git) throws Exception {
        Class<?> activatorClass = egit.loadClass("org.eclipse.egit.core.Activator");
        Object activator = invoke(activatorClass.getMethod("getDefault"), null);
        Object util = invoke(activatorClass.getMethod("getRepositoryUtil"), activator);
        invoke(util.getClass().getMethod("addConfiguredRepository", File.class), util, git.toFile());
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }

    private static void backupMetadata(PackageProject project, Path destination) throws Exception {
        Files.createDirectories(destination);
        for (String name : List.of(".project", ".classpath")) {
            Path source = project.source.resolve(name);
            if (Files.isRegularFile(source)) Files.copy(source, destination.resolve(name), StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void setAutoBuilding(IWorkspace workspace, boolean value) throws Exception {
        IWorkspaceDescription description = workspace.getDescription();
        description.setAutoBuilding(value);
        workspace.setDescription(description);
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing plan field: " + name);
        return value;
    }

    private static void writeResult(Path result, Properties values) throws Exception {
        Path temp = Files.createTempFile(result.getParent(), "result-", ".tmp");
        try {
            try (java.io.Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { values.store(out, "Designer import outcome"); }
            Files.move(temp, result, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private static final class PackageProject {
        final String name;
        final Path source;
        final Path gitDirectory;
        final IProjectDescription description;
        PackageProject(String name, Path source, Path gitDirectory, IProjectDescription description) {
            this.name = name; this.source = source; this.gitDirectory = gitDirectory; this.description = description;
        }
    }
}
