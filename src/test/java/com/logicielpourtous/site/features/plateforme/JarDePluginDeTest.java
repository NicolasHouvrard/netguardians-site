package com.logicielpourtous.site.features.plateforme;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Aide de test : fabrique un jar de plugin minimal (manifeste + migrations, et classes compilées à la volée). */
public final class JarDePluginDeTest {

	private JarDePluginDeTest() {
	}

	/**
	 * Crée {@code <dossier>/<nom>-<version>.jar} contenant {@code plugin.properties} (nom et version, plus les
	 * propriétés fournies) et {@code db/plugin/<nom>/<fichier>} pour chaque migration.
	 */
	public static Path creer(Path dossier, String nom, String version, Map<String, String> proprietes,
			Map<String, String> migrations) {
		return creer(dossier, nom, version, proprietes, migrations, Map.of());
	}

	/**
	 * Comme {@link #creer(Path, String, String, Map, Map)}, et ajoute au jar les classes compilées à partir de
	 * {@code sources} (nom de classe complet, par exemple {@code x.Hello}, vers son code source Java). La compilation
	 * utilise le classpath du test ({@code java.class.path}).
	 */
	public static Path creer(Path dossier, String nom, String version, Map<String, String> proprietes,
			Map<String, String> migrations, Map<String, String> sources) {
		try {
			Files.createDirectories(dossier);
			Path jar = dossier.resolve(nom + "-" + version + ".jar");
			StringBuilder props = new StringBuilder();
			props.append("nom=").append(nom).append('\n');
			props.append("version=").append(version).append('\n');
			proprietes.forEach((cle, valeur) -> props.append(cle).append('=').append(valeur).append('\n'));
			try (OutputStream sortie = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(sortie)) {
				ajouter(jos, "plugin.properties", props.toString());
				for (Map.Entry<String, String> m : migrations.entrySet()) {
					ajouter(jos, "db/plugin/" + nom + "/" + m.getKey(), m.getValue());
				}
				if (!sources.isEmpty()) {
					Path classes = compiler(dossier.resolve("classes-" + nom + "-" + version), sources);
					try (Stream<Path> fichiers = Files.walk(classes)) {
						// Entrées de dossiers : indispensables au scan de classpath de Spring (getResources("paquet/")).
						for (Path d : fichiers.filter(f -> Files.isDirectory(f) && !f.equals(classes)).toList()) {
							jos.putNextEntry(new JarEntry(classes.relativize(d).toString().replace('\\', '/') + "/"));
							jos.closeEntry();
						}
					}
					try (Stream<Path> fichiers = Files.walk(classes)) {
						for (Path f : fichiers.filter(Files::isRegularFile).toList()) {
							jos.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
							Files.copy(f, jos);
							jos.closeEntry();
						}
					}
				}
			}
			return jar;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Migration V1 du plugin de test « testnotes » : crée {@code plugin_testnotes.note(id, texte)}. */
	public static final String MIGRATION_NOTES_V1 = """
			CREATE TABLE plugin_testnotes.note (
			    id BIGSERIAL PRIMARY KEY,
			    texte VARCHAR(255) NOT NULL
			);
			""";

	private static final String NOTE = """
			package notes;
			import jakarta.persistence.*;
			@Entity
			@Table(name = "note")
			public class Note {
			    @Id
			    @GeneratedValue(strategy = GenerationType.IDENTITY)
			    private Long id;
			    @Column(nullable = false)
			    private String texte;
			    public Note() {}
			    public Note(String texte) { this.texte = texte; }
			    public Long getId() { return id; }
			    public String getTexte() { return texte; }
			    public void setTexte(String texte) { this.texte = texte; }
			}
			""";

	private static final String NOTE_REPOSITORY = """
			package notes;
			import org.springframework.data.jpa.repository.JpaRepository;
			public interface NoteRepository extends JpaRepository<Note, Long> {
			}
			""";

	private static final String NOTES_CONFIGURATION = """
			package notes;
			import org.springframework.context.annotation.ComponentScan;
			import org.springframework.context.annotation.Configuration;
			import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
			import org.springframework.transaction.annotation.EnableTransactionManagement;
			@Configuration
			@ComponentScan("notes")
			@EnableJpaRepositories(basePackages = "notes")
			@EnableTransactionManagement
			public class NotesConfiguration {
			}
			""";

	private static final String NOTES_CONTROLLER = """
			package notes;
			import java.util.List;
			import java.util.Map;
			import org.springframework.web.bind.annotation.*;
			@RestController
			@RequestMapping("/notes")
			public class NotesController {
			    private final NoteRepository depot;
			    public NotesController(NoteRepository depot) { this.depot = depot; }
			    @GetMapping
			    public List<Note> lister() { return depot.findAll(); }
			    @PostMapping
			    public Note creer(@RequestBody Map<String, String> corps) { return depot.save(new Note(corps.get("texte"))); }
			    @GetMapping("/lent")
			    public String lent(@RequestParam("ms") long ms) throws InterruptedException {
			        Thread.sleep(ms);
			        return "ok";
			    }
			    @GetMapping("/async")
			    public org.springframework.web.context.request.async.DeferredResult<String> asynchrone(@RequestParam("ms") long ms) {
			        org.springframework.web.context.request.async.DeferredResult<String> resultat = new org.springframework.web.context.request.async.DeferredResult<>();
			        Thread t = new Thread(() -> {
			            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			            resultat.setResult("ok");
			        });
			        t.setDaemon(true);
			        t.start();
			        return resultat;
			    }
			    @GetMapping("/boom")
			    public String boom() { throw new RuntimeException("boom"); }
			}
			""";

	/**
	 * Plugin de test complet « testnotes » : entité {@code Note(id, texte)}, dépôt Spring Data, configuration
	 * ({@code @EnableJpaRepositories}, {@code @EnableTransactionManagement}) et {@code NotesController}
	 * ({@code GET/POST /notes}, {@code GET /notes/lent?ms=N}, {@code GET /notes/boom}). Les migrations fournies
	 * (nom de fichier vers SQL) sont embarquées telles quelles ; {@link #MIGRATION_NOTES_V1} est la V1 usuelle.
	 */
	public static Path pluginNotes(Path dossier, String version, Map<String, String> migrations) {
		return creer(dossier, "testnotes", version,
				Map.of("coeur-min", "0.0.0", "mise-a-jour", "chaud", "paquet", "notes", "configuration",
						"notes.NotesConfiguration"),
				migrations, Map.of("notes.Note", NOTE, "notes.NoteRepository", NOTE_REPOSITORY,
						"notes.NotesConfiguration", NOTES_CONFIGURATION, "notes.NotesController", NOTES_CONTROLLER));
	}

	/** Compile les sources (classe complète vers code) dans {@code sortie} et renvoie ce dossier. */
	private static Path compiler(Path sortie, Map<String, String> sources) throws IOException {
		Path dossierSources = sortie.resolveSibling(sortie.getFileName() + "-src");
		Files.createDirectories(sortie);
		List<String> arguments = new ArrayList<>(
				List.of("-classpath", System.getProperty("java.class.path"), "-d", sortie.toString(), "-proc:none"));
		for (Map.Entry<String, String> s : sources.entrySet()) {
			Path fichier = dossierSources.resolve(s.getKey().replace('.', '/') + ".java");
			Files.createDirectories(fichier.getParent());
			Files.writeString(fichier, s.getValue());
			arguments.add(fichier.toString());
		}
		JavaCompiler compilateur = ToolProvider.getSystemJavaCompiler();
		if (compilateur.run(null, null, null, arguments.toArray(String[]::new)) != 0) {
			throw new IllegalStateException("Compilation des sources de test échouée : " + sources.keySet());
		}
		return sortie;
	}

	private static void ajouter(JarOutputStream jos, String chemin, String contenu) throws IOException {
		jos.putNextEntry(new JarEntry(chemin));
		jos.write(contenu.getBytes(StandardCharsets.UTF_8));
		jos.closeEntry();
	}
}
