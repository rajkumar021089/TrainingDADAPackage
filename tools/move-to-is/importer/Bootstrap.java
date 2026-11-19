import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Java 11+ source launcher: compile the helper against the local Designer. */
public class Bootstrap {
    private static final String BUNDLE = "nbk.tools.lsdimport";

    public static void main(String[] args) {
        try {
            if (args.length != 4) {
                throw new IllegalArgumentException("Expected: eclipse-directory source-directory run-directory workspace");
            }
            setup(Path.of(args[0]).toRealPath(), Path.of(args[1]).toRealPath(),
                    Path.of(args[2]).toRealPath(), Path.of(args[3]).toRealPath());
        } catch (Exception error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static void setup(Path eclipse, Path sources, Path run, Path workspace) throws Exception {
        if (!Files.isDirectory(workspace.resolve(".metadata"))) {
            throw new IOException("Use an existing Designer workspace: " + workspace);
        }
        checkWorkspaceLock(workspace);
        validatePlanProjects(run.resolve("import.properties"), workspace);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("The selected Java is a JRE. Supply a Designer-compatible JDK with --java.");
        }
        Set<Path> classpath = collectClasspath(eclipse);
        if (classpath.isEmpty()) {
            throw new IOException("No Eclipse bundle jars found under " + eclipse);
        }
        checkBundle(classpath, "org.eclipse.egit.core");
        boolean hasLsd = false;
        for (Path entry : classpath) {
            String id = bundleId(entry);
            if (id.toLowerCase(java.util.Locale.ROOT).contains("vcsintegration")) {
                hasLsd = true;
                System.out.println("Local Service Development bundle: " + id);
            }
        }
        if (!hasLsd) {
            throw new IOException("Local Version Control Integration bundle not found. Install that Designer feature first.");
        }
        Path classes = Files.createDirectories(run.resolve("classes"));
        List<String> options = new ArrayList<>();
        options.add("-source"); options.add("11");
        options.add("-target"); options.add("11");
        options.add("-encoding"); options.add("UTF-8");
        options.add("-classpath");
        options.add(String.join(File.pathSeparator, classpath.stream().map(Path::toString).toArray(String[]::new)));
        options.add("-d"); options.add(classes.toString());
        options.add(sources.resolve("nbk/tools/lsdimport/Startup.java").toString());
        int status = compiler.run(null, System.out, System.err, options.toArray(new String[0]));
        if (status != 0) {
            throw new IOException("Helper compilation failed against this Designer installation (exit " + status + ").");
        }
        Manifest manifest = new Manifest();
        Attributes a = manifest.getMainAttributes();
        a.putValue("Manifest-Version", "1.0");
        a.putValue("Bundle-ManifestVersion", "2");
        a.putValue("Bundle-SymbolicName", BUNDLE + ";singleton:=true");
        a.putValue("Bundle-Version", "1.0.0");
        a.putValue("Bundle-Name", "NBK batch Local Service Development importer");
        a.putValue("Bundle-RequiredExecutionEnvironment", "JavaSE-11");
        a.putValue("Require-Bundle", "org.eclipse.core.runtime,org.eclipse.core.resources,org.eclipse.ui,org.eclipse.team.core,org.eclipse.jdt.core,org.eclipse.egit.core");
        a.putValue("Bundle-ActivationPolicy", "lazy");
        Path jar = run.resolve(BUNDLE + "_1.0.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("plugin.xml"));
            out.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plugin>\n"
                    + " <extension point=\"org.eclipse.ui.startup\">\n"
                    + "  <startup class=\"nbk.tools.lsdimport.Startup\"/>\n"
                    + " </extension>\n</plugin>\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            try (java.util.stream.Stream<Path> stream = Files.walk(classes)) {
                for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                    out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
                    Files.copy(file, out);
                    out.closeEntry();
                }
            }
        }
        Path dropins = Files.createDirectories(eclipse.resolve("dropins"));
        Path destination = dropins.resolve(jar.getFileName());
        if (Files.exists(destination)) {
            Files.copy(destination, run.resolve("previous-import-helper.jar"), StandardCopyOption.REPLACE_EXISTING);
        }
        Path staged = Files.createTempFile(dropins, "nbk-import-", ".tmp");
        try {
            Files.copy(jar, staged, StandardCopyOption.REPLACE_EXISTING);
            Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
        System.out.println("Installed source-built helper: " + destination);
        System.out.println("The helper is idle on ordinary Designer launches without -Dnbk.lsdimport.plan.");
    }

    static void checkWorkspaceLock(Path workspace) throws IOException {
        Path lockPath = workspace.resolve(".metadata/.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            try (FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    throw new IOException("Designer is using this workspace. Close it first: " + workspace);
                }
            } catch (OverlappingFileLockException error) {
                throw new IOException("The workspace is already locked: " + workspace, error);
            }
        }
    }

    static void validatePlanProjects(Path planFile, Path workspace) throws Exception {
        java.util.Properties plan = new java.util.Properties();
        try (java.io.Reader in = Files.newBufferedReader(planFile, StandardCharsets.UTF_8)) { plan.load(in); }
        if (!"1".equals(plan.getProperty("format"))) throw new IOException("Unsupported import plan");
        if (!Files.isSameFile(Path.of(plan.getProperty("workspace")), workspace)) throw new IOException("Plan workspace mismatch");
        int count = Integer.parseInt(plan.getProperty("count"));
        if (count < 1 || count > 10000) throw new IOException("Invalid package count");
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder parser = factory.newDocumentBuilder();
        Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            String prefix = "package." + i + ".";
            String name = plan.getProperty(prefix + "name", "");
            if (!name.matches("NBK[A-Za-z0-9_.-]*") || !names.add(name.toLowerCase(java.util.Locale.ROOT))) {
                throw new IOException("Invalid or repeated package: " + name);
            }
            Path source = Path.of(plan.getProperty(prefix + "path")).toRealPath();
            if (!Files.isRegularFile(source.resolve("manifest.v3"))) throw new IOException("Missing package manifest: " + source);
            org.w3c.dom.Element root = parser.parse(source.resolve(".project").toFile()).getDocumentElement();
            String projectName = null;
            for (org.w3c.dom.Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && "name".equals(child.getNodeName())) projectName = child.getTextContent().trim();
            }
            if (!"projectDescription".equals(root.getTagName()) || !name.equals(projectName)) {
                throw new IOException("Package directory and .project name differ: " + source + " (" + projectName + ")");
            }
            if (!"classpath".equals(parser.parse(source.resolve(".classpath").toFile()).getDocumentElement().getTagName())) {
                throw new IOException("Invalid .classpath: " + source);
            }
        }
    }

    static Set<Path> collectClasspath(Path eclipse) throws IOException {
        Set<Path> entries = new LinkedHashSet<>();
        Path info = eclipse.resolve("configuration/org.eclipse.equinox.simpleconfigurator/bundles.info");
        if (Files.isRegularFile(info)) {
            for (String line : Files.readAllLines(info, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split(",", 5);
                if (fields.length < 3) continue;
                Path resolved = resolveBundle(eclipse, fields[2]);
                if (resolved != null) entries.add(resolved.toRealPath());
            }
        }
        Set<String> selectedIds = new java.util.HashSet<>();
        for (Path selected : entries) {
            String id = bundleId(selected);
            if (!id.isEmpty()) selectedIds.add(id);
        }
        // Support a normal non-pooled installation and the common Software AG layout.
        for (Path dir : List.of(eclipse.resolve("plugins"), eclipse.resolve("../../common/eclipse/plugins").normalize())) {
            if (!Files.isDirectory(dir)) continue;
            try (java.util.stream.Stream<Path> children = Files.list(dir)) {
                for (Path path : (Iterable<Path>) children::iterator) {
                    if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")
                            || Files.isDirectory(path) && Files.exists(path.resolve("META-INF/MANIFEST.MF"))) {
                        // Prefer the exact configured version where bundles.info already names this bundle.
                        String id = bundleId(path);
                        if (id.isEmpty() || selectedIds.add(id)) entries.add(path.toRealPath());
                    }
                }
            }
        }
        return entries;
    }

    static Path resolveBundle(Path eclipse, String location) {
        String value = location.trim();
        if (value.startsWith("reference:")) value = value.substring("reference:".length());
        if (value.startsWith("file:")) {
            try {
                java.net.URI uri = java.net.URI.create(value);
                if (!uri.isOpaque() && uri.getPath() != null && uri.getPath().startsWith("/")) {
                    Path absolute = Path.of(uri);
                    return Files.exists(absolute) ? absolute : null;
                }
                value = uri.getSchemeSpecificPart();
            } catch (IllegalArgumentException invalid) { return null; }
        }
        try {
            Path relative = Path.of(value);
            if (relative.isAbsolute()) return Files.exists(relative) ? relative : null;
            for (Path base : List.of(eclipse, eclipse.resolve("configuration"))) {
                Path resolved = base.resolve(relative).normalize();
                if (Files.exists(resolved)) return resolved;
            }
        } catch (RuntimeException invalid) { return null; }
        return null;
    }

    static String bundleId(Path path) throws IOException {
        Manifest manifest;
        if (Files.isDirectory(path)) {
            Path file = path.resolve("META-INF/MANIFEST.MF");
            if (!Files.isRegularFile(file)) return "";
            try (InputStream in = Files.newInputStream(file)) { manifest = new Manifest(in); }
        } else {
            try (JarFile jar = new JarFile(path.toFile())) { manifest = jar.getManifest(); }
        }
        String name = manifest == null ? null : manifest.getMainAttributes().getValue("Bundle-SymbolicName");
        return name == null ? "" : name.split(";", 2)[0].trim();
    }

    static void checkBundle(Set<Path> entries, String required) throws IOException {
        for (Path entry : entries) if (required.equals(bundleId(entry))) return;
        throw new IOException("Required Designer bundle not found: " + required);
    }
}
