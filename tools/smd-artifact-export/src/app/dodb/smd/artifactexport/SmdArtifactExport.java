package app.dodb.smd.artifactexport;

import org.springframework.boot.SpringApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

// Boots the application the way a @SpringBootTest does (its own main method is not run), writes the SMD Artifact
// and exits.
//
//   SmdArtifactExport --main-class <class> --out <file> [--app <name>] [--config-class <class>]... [--property <key=value>]...
public final class SmdArtifactExport {

    private SmdArtifactExport() {
    }

    public static void main(String[] args) {
        try {
            export(args);
        } catch (Throwable e) {
            System.err.println("smd-artifact-export failed:");
            e.printStackTrace();
            System.exit(1);
        }
        // Event-store pollers and durable-delivery workers keep threads alive after the context closes.
        System.exit(0);
    }

    private static void export(String[] args) throws Exception {
        String mainClassName = null;
        String out = null;
        String app = null;
        var configClassNames = new ArrayList<String>();
        var springArgs = new ArrayList<String>(List.of("--server.port=0"));
        for (int i = 0; i < args.length; i++) {
            var value = i + 1 < args.length ? args[i + 1] : null;
            switch (args[i]) {
                case "--main-class" -> mainClassName = value;
                case "--out" -> out = value;
                case "--app" -> app = value;
                case "--config-class" -> configClassNames.add(value);
                case "--property" -> springArgs.add("--" + value);
                default -> throw new IllegalArgumentException("Unknown argument '" + args[i] + "'; expected --main-class, --out, --app, --config-class or --property");
            }
            if (value == null) {
                throw new IllegalArgumentException("Argument " + args[i] + " requires a value");
            }
            i++;
        }
        if (mainClassName == null || out == null) {
            throw new IllegalArgumentException("--main-class and --out are required");
        }

        var classLoader = SmdArtifactExport.class.getClassLoader();
        var mainClass = Class.forName(mainClassName, false, classLoader);
        var sources = new ArrayList<Class<?>>(List.of(mainClass));
        for (var configClassName : configClassNames) {
            sources.add(Class.forName(configClassName, false, classLoader));
        }
        try (var context = new SpringApplication(sources.toArray(Class<?>[]::new)).run(springArgs.toArray(String[]::new))) {
            var appName = app != null ? app : context.getEnvironment().getProperty("spring.application.name", mainClass.getSimpleName());
            var json = Json.write(new SmdArtifact(context, mainClass).build(appName));
            write(Path.of(out), json);
        }
    }

    private static void write(Path out, String json) throws IOException {
        var parent = out.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        var temporary = Files.createTempFile(parent, ".smd-artifact", ".json");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        Files.move(temporary, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("Wrote the SMD Artifact to " + out);
    }
}
