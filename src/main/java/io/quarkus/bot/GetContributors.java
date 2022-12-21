//DEPS io.quarkus.platform:quarkus-bom:2.15.0.Final@pom
//DEPS io.quarkus:quarkus-picocli
//DEPS io.quarkiverse.githubapi:quarkus-github-api:1.313.1
//DEPS net.gcardone.junidecode:junidecode:0.4.1

//JAVAC_OPTIONS -parameters
//JAVA_OPTIONS -Djava.util.logging.manager=org.jboss.logmanager.LogManager

//Q:CONFIG quarkus.log.level=SEVERE
//Q:CONFIG quarkus.banner.enabled=false
package io.quarkus.bot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import net.gcardone.junidecode.Junidecode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "get-contributors", mixinStandardHelpOptions = true)
public class GetContributors implements Callable<Integer> {

    private static final Path CLONE_DIRECTORY = Path.of("get-contributors-repositories");
    private static final Path QUARKIVERSE_CONTRIBUTORS_FILE = Path.of("quarkiverse.csv");
    private static final Path PLATFORM_WITHOUT_QUARKIVERSE_FILE = Path.of("platform-without-quarkiverse.csv");
    private static final Path PLATFORM_PLUS_QUARKIVERSE_FILE = Path.of("platform-plus-quarkiverse.csv");

    private static final Map<String, String> PLATFORM_PROJECTS = Map.of(
            "apache/camel-quarkus", ".",
            "kiegroup/kogito-runtimes", "quarkus",
            "kiegroup/optaplanner", "optaplanner-quarkus-integration",
            "datastax/cassandra-quarkus", ".",
            "amqphub/quarkus-qpid-jms", ".",
            "hazelcast/quarkus-hazelcast-client", ".",
            "debezium/debezium", "debezium-quarkus-outbox",
            "Blazebit/blaze-persistence", "integration/quarkus");

    private static final Set<String> IGNORE_SET = Set.of("GitHub Action", "GitHub", "debezium-builder", "Debezium Builder",
            "bsig-cloud gh bot", "Jenkins CI", "kie-ci", "Ubuntu", "quarkiversebot");

    private static final String BOT = "[bot]";

    private static final String NO_REPLY = "@users.noreply.github.com";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    @Parameters(paramLabel = "<since>", description = "Date from which we consider the contributions")
    Date since;

    @Option(names = "--sort", defaultValue = "name")
    Sort sort;

    @Override
    public Integer call() throws Exception {
        if (Files.exists(CLONE_DIRECTORY)) {
            throw new IllegalStateException(CLONE_DIRECTORY + " already exists, please delete it before starting the script");
        }

        Files.createDirectory(CLONE_DIRECTORY);
        Files.deleteIfExists(QUARKIVERSE_CONTRIBUTORS_FILE);
        Files.deleteIfExists(PLATFORM_WITHOUT_QUARKIVERSE_FILE);
        Files.deleteIfExists(PLATFORM_PLUS_QUARKIVERSE_FILE);

        final GitHub github = GitHub.connectAnonymously();

        // Get Quarkiverse contributors
        List<GHRepository> quarkiverseExtensions = github.searchRepositories()
                .org("quarkiverse")
                .topic("quarkus-extension")
                .order(GHDirection.ASC)
                .list().withPageSize(200).toList();

        Map<String, Contribution> allNameContributionMap = new HashMap<>();
        Map<String, Contribution> allEmailContributionMap = new HashMap<>();
        List<Contribution> allContributions = new ArrayList<>();

        Map<String, Contribution> quarkiverseNameContributionMap = new HashMap<>();
        Map<String, Contribution> quarkiverseEmailContributionMap = new HashMap<>();
        List<Contribution> quarkiverseContributions = new ArrayList<>();

        System.out.println("Analyzing " + quarkiverseExtensions.size() + " Quarkiverse repositories");

        for (GHRepository quarkiverseExtension : quarkiverseExtensions) {
            analyzeRepository(quarkiverseExtension, ".", allNameContributionMap, allEmailContributionMap, allContributions,
                    quarkiverseNameContributionMap, quarkiverseEmailContributionMap, quarkiverseContributions);
        }

        writeContributions(quarkiverseContributions, QUARKIVERSE_CONTRIBUTORS_FILE);

        // Get platform projects contributors
        System.out.println("");
        System.out.println("Analyzing " + PLATFORM_PROJECTS.size() + " Platform repositories");

        Map<String, Contribution> platformNameContributionMap = new HashMap<>();
        Map<String, Contribution> platformEmailContributionMap = new HashMap<>();
        List<Contribution> platformContributions = new ArrayList<>();

        for (Entry<String, String> otherProjectEntry : PLATFORM_PROJECTS.entrySet()) {
            GHRepository repository = github.getRepository(otherProjectEntry.getKey());
            analyzeRepository(repository, otherProjectEntry.getValue(), allNameContributionMap, allEmailContributionMap,
                    allContributions,
                    platformNameContributionMap, platformEmailContributionMap, platformContributions);
        }

        writeContributions(platformContributions, PLATFORM_WITHOUT_QUARKIVERSE_FILE);

        writeContributions(allContributions, PLATFORM_PLUS_QUARKIVERSE_FILE);

        return 0;
    }

    private void analyzeRepository(GHRepository repository, String root,
            Map<String, Contribution> allNameContributionMap,
            Map<String, Contribution> allEmailContributionMap,
            List<Contribution> allContributions,
            Map<String, Contribution> currentNameContributionMap,
            Map<String, Contribution> currentEmailContributionMap,
            List<Contribution> currentContributions) throws IOException, InterruptedException {
        System.out.println(" > Analyzing " + repository.getFullName());

        Path repositoryDirectory = CLONE_DIRECTORY.resolve(repository.getName());

        if (Files.exists(repositoryDirectory)) {
            System.out.println("    ... already analyzed, skipping");
            return;
        }

        Process process = new ProcessBuilder("git", "clone", repository.getSshUrl())
                .directory(CLONE_DIRECTORY.toFile())
                .start();
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        if (exitCode > 0) {
            throw new IllegalStateException("Error cloning " + repository.getFullName() + " - exit code: "
                    + exitCode + " - error: " + error);
        }

        Path errorFile = repositoryDirectory.resolve("error.txt");
        Path contributionsFile = repositoryDirectory.resolve("contributions.txt");

        process = new ProcessBuilder("git", "--no-pager", "log", "--format=%an;%ae", "--no-merges", "--since",
                FORMATTER.format(since.toInstant()), root)
                        .directory(repositoryDirectory.toFile())
                        .redirectError(errorFile.toFile())
                        .redirectOutput(contributionsFile.toFile())
                        .start();

        process.waitFor();

        parseContributions(repository, contributionsFile, allNameContributionMap, allEmailContributionMap, allContributions,
                currentNameContributionMap, currentEmailContributionMap, currentContributions);
    }

    private void parseContributions(GHRepository repository,
            Path contributionsFile,
            Map<String, Contribution> allNameContributionMap,
            Map<String, Contribution> allEmailContributionMap,
            List<Contribution> allContributions,
            Map<String, Contribution> currentNameContributionMap,
            Map<String, Contribution> currentEmailContributionMap,
            List<Contribution> currentContributions)
            throws IOException {
        for (String line : Files.readAllLines(contributionsFile)) {
            String[] tokens = line.split(";");
            String authorName = tokens[0].trim();
            String authorEmail = tokens.length > 1 ? tokens[1].trim() : "";

            if (ignore(authorName)) {
                continue;
            }

            pushContributions(repository, authorName, authorEmail, allNameContributionMap, allEmailContributionMap,
                    allContributions);
            pushContributions(repository, authorName, authorEmail, currentNameContributionMap, currentEmailContributionMap,
                    currentContributions);
        }
    }

    private void pushContributions(GHRepository repository, String authorName, String authorEmail,
            Map<String, Contribution> nameContributionMap,
            Map<String, Contribution> emailContributionMap,
            List<Contribution> contributions) {
        String normalizedAuthorName = Junidecode.unidecode(authorName).toLowerCase(Locale.ROOT);
        String username = "";
        if (isNoReply(authorEmail)) {
            username = extractUsername(authorEmail);
            authorEmail = "";
        }

        Contribution contribution = nameContributionMap.get(normalizedAuthorName);
        if (contribution != null) {
            contribution.commits++;
            contribution.repositories.add(repository.getFullName());
            if (contribution.email.equals(authorEmail)) {
                // nothing, all is good
            } else if (contribution.email.isBlank()) {
                contribution.email = authorEmail;
                contribution.username = "";
                emailContributionMap.put(authorEmail, contribution);
            } else {
                emailContributionMap.put(authorEmail, contribution);
            }
        } else if (!authorEmail.isBlank()) {
            contribution = emailContributionMap.get(authorEmail);
            if (contribution != null) {
                contribution.commits++;
                contribution.repositories.add(repository.getFullName());
                if (contribution.author.equals(authorName)) {
                    // nothing, all is good
                } else {
                    if (authorName.length() > contribution.author.length()) {
                        contribution.author = authorName;
                    }
                    nameContributionMap.put(normalizedAuthorName, contribution);
                }
            } else {
                contribution = new Contribution(authorName, authorEmail, username, repository.getFullName());
                nameContributionMap.put(normalizedAuthorName, contribution);
                emailContributionMap.put(authorEmail, contribution);
                contributions.add(contribution);
            }
        } else {
            contribution = new Contribution(authorName, authorEmail, username, repository.getFullName());
            nameContributionMap.put(normalizedAuthorName, contribution);
            contributions.add(contribution);
        }
    }

    private void writeContributions(List<Contribution> contributions, Path file) throws IOException {
        sort.sort(contributions);

        Files.write(file,
                ("Name;Email;GH handle if no email;Commits;Repositories\n").getBytes(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        for (Contribution contribution : contributions) {
            Files.write(file,
                    ("\"" + contribution.author + "\";\"" + contribution.email + "\";" + contribution.username + ";"
                            + contribution.commits + ";"
                            + String.join(",", contribution.repositories) + "\n")
                                    .getBytes(),
                    StandardOpenOption.APPEND);
        }
    }

    private boolean ignore(String username) {
        if (username.contains(BOT)) {
            return true;
        }

        if (IGNORE_SET.contains(username)) {
            return true;
        }

        return false;
    }

    private boolean isNoReply(String email) {
        return email.contains(NO_REPLY);
    }

    private String extractUsername(String email) {
        String username = email.substring(0, email.indexOf('@'));
        if (username.contains("+")) {
            username = username.substring(email.indexOf('+') + 1);
        }
        return username;
    }

    public class Contribution {

        public String author;
        public String email;
        public String username;
        public int commits;
        public Set<String> repositories = new TreeSet<>();

        public Contribution(String author, String email, String username, String repository) {
            this.author = author;
            this.email = email;
            this.username = username;
            this.commits = 1;
            this.repositories.add(repository);
        }
    }

    public enum Sort {

        name {
            @Override
            void sort(List<Contribution> contributions) {
                contributions.sort((c1, c2) -> c1.author.compareToIgnoreCase(c2.author));
            }
        },
        commits {
            @Override
            void sort(List<Contribution> contributions) {
                contributions.sort((c1, c2) -> Integer.valueOf(c2.commits).compareTo(Integer.valueOf(c1.commits)));
            }
        };

        abstract void sort(List<Contribution> contributions);
    }
}
