import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProjectDescription;

/** Checks migration/preservation using real Eclipse API interfaces. No vendor runtime is simulated. */
public class MetadataCheck {
    static final String JAVA = "org.eclipse.jdt.core.javabuilder";
    static final String NEW = "com.webmethods.sdev.vcsintegration.ISPackageBuilder";
    static final String NATURE = "com.webmethods.sdev.vcsintegration.nature";

    static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    static ICommand command(String name, Map<String, String> arguments) {
        Map<String, Object> state = new HashMap<>();
        state.put("name", name); state.put("arguments", arguments);
        return (ICommand) Proxy.newProxyInstance(MetadataCheck.class.getClassLoader(), new Class<?>[]{ICommand.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "getBuilderName": return state.get("name");
                    case "setBuilderName": state.put("name", args[0]); return null;
                    case "getArguments": return state.get("arguments");
                    default: return defaultValue(method.getReturnType());
                    }
                });
    }

    static IProjectDescription description(List<String> natures, List<ICommand> commands) {
        Map<String, Object> state = new HashMap<>();
        state.put("natures", natures.toArray(new String[0]));
        state.put("commands", commands.toArray(new ICommand[0]));
        return (IProjectDescription) Proxy.newProxyInstance(MetadataCheck.class.getClassLoader(),
                new Class<?>[]{IProjectDescription.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "getNatureIds": return state.get("natures");
                    case "setNatureIds": state.put("natures", args[0]); return null;
                    case "getBuildSpec": return state.get("commands");
                    case "setBuildSpec": state.put("commands", args[0]); return null;
                    case "newCommand": return command("", Map.of());
                    default: return defaultValue(method.getReturnType());
                    }
                });
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Method configure = Class.forName("nbk.tools.lsdimport.Startup")
                .getDeclaredMethod("configure", IProjectDescription.class, String.class, String.class);
        configure.setAccessible(true);
        ICommand java = command(JAVA, Map.of("preserve", "java-settings"));
        ICommand other = command("example.custom.builder", Map.of("keep", "custom-settings"));
        IProjectDescription migrated = description(new ArrayList<>(List.of("example.custom.nature",
                "com.softwareag.designer.vcsintegration.nature")), new ArrayList<>(List.of(other, java,
                command("com.softwareag.designer.vcsintegration.ISPackageBuilder", Map.of()))));
        configure.invoke(null, migrated, NATURE, NEW);
        require(Arrays.asList(migrated.getNatureIds()).contains("example.custom.nature"), "Unrelated nature was removed");
        require(Arrays.asList(migrated.getNatureIds()).contains(NATURE), "Installed LSD nature was not added");
        require(Arrays.stream(migrated.getNatureIds()).noneMatch(n -> n.startsWith("com.softwareag")), "Old LSD nature remained");
        require(Arrays.stream(migrated.getBuildSpec()).anyMatch(c -> c == other), "Custom builder/arguments were replaced");
        require(Arrays.stream(migrated.getBuildSpec()).anyMatch(c -> c == java), "Java builder/arguments were replaced");
        require("custom-settings".equals(other.getArguments().get("keep")), "Custom builder arguments changed");
        require(Arrays.stream(migrated.getBuildSpec()).filter(c -> NEW.equals(c.getBuilderName())).count() == 1, "New LSD builder count");
        require(Arrays.stream(migrated.getBuildSpec()).filter(c -> JAVA.equals(c.getBuilderName())).count() == 1, "Java builder duplicated");
        configure.invoke(null, migrated, NATURE, NEW);
        require(Arrays.stream(migrated.getBuildSpec()).filter(c -> NEW.equals(c.getBuilderName())).count() == 1, "Repeated configuration duplicated builder");
        System.out.println("PASS: LSD namespace migration preserves custom/Java builders and is repeatable");

        ICommand installed = command(NEW, Map.of("installed-option", "retain"));
        IProjectDescription current = description(new ArrayList<>(List.of(NATURE)), new ArrayList<>(List.of(installed)));
        configure.invoke(null, current, NATURE, NEW);
        require(Arrays.stream(current.getBuildSpec()).anyMatch(c -> c == installed), "Installed builder arguments were replaced");
        require(Arrays.stream(current.getBuildSpec()).filter(c -> JAVA.equals(c.getBuilderName())).count() == 1, "Missing Java builder was not added once");
        System.out.println("PASS: current LSD builder arguments are preserved and missing Java builder is added");
    }
}
