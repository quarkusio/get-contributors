//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.quarkus.platform:quarkus-bom:3.0.2.Final@pom
//DEPS io.quarkus:quarkus-picocli
//DEPS io.quarkiverse.githubapi:quarkus-github-api:1.314.1
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

@Command(name = "get-contributors", mixinStandardHelpOptions = true)
public class GetContributors implements Callable<Integer> {

    private static final Path CLONE_DIRECTORY = Path.of("get-contributors-repositories");
    private static final Path QUARKUS_CONTRIBUTORS_FILE = Path.of("contributors-quarkus.csv");
    private static final Path QUARKIVERSE_CONTRIBUTORS_FILE = Path.of("contributors-quarkiverse.csv");
    private static final Path PLATFORM_CONTRIBUTORS_FILE = Path.of("contributors-platform.csv");
    private static final Path WEBSITE_TRANSLATIONS_CONTRIBUTORS_FILE = Path.of("contributors-website-translations.csv");
    private static final Path ALL_CONTRIBUTORS_FILE = Path.of("contributors-all.csv");

    private static final Map<String, String> PLATFORM_PROJECTS = Map.ofEntries(
            Map.entry("quarkusio/quarkus-platform", "."),
            Map.entry("apache/camel-quarkus", "."),
            Map.entry("kiegroup/kogito-runtimes", "quarkus"),
            Map.entry("kiegroup/optaplanner", "optaplanner-quarkus-integration"),
            Map.entry("datastax/cassandra-quarkus", "."),
            Map.entry("amqphub/quarkus-qpid-jms", "."),
            Map.entry("hazelcast/quarkus-hazelcast-client", "."),
            Map.entry("debezium/debezium", "debezium-quarkus-outbox"),
            Map.entry("Blazebit/blaze-persistence", "integration/quarkus"),
            Map.entry("quarkiverse/quarkus-operator-sdk", "."),
            Map.entry("quarkiverse/quarkus-amazon-services", "."),
            Map.entry("quarkiverse/quarkus-config-extensions", "consul"));

    private static final Set<String> IGNORE_SET = Set.of("GitHub Action", "GitHub", "debezium-builder", "Debezium Builder",
            "bsig-cloud gh bot", "Jenkins CI", "kie-ci", "Ubuntu", "quarkiversebot");

    private static final String BOT = "[bot]";

    private static final String NO_REPLY = "@users.noreply.github.com";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    @Option(names = "--since", paramLabel = "<since>", description = "Date from which we consider the contributions")
    Date since;

    @Option(names = { "--main-repository-branch" }, paramLabel = "<mainRepositoryBranch>", description = "Branch of the main repository in which we consider the contributions", defaultValue = "main")
    String mainRepositoryBranch;

    @Option(names = "--sort", defaultValue = "name")
    Sort sort;

    @Override
    public Integer call() throws Exception {
        if (Files.exists(CLONE_DIRECTORY)) {
            throw new IllegalStateException(CLONE_DIRECTORY + " already exists, please delete it before starting the script");
        }

        Files.createDirectory(CLONE_DIRECTORY);
        Files.deleteIfExists(QUARKUS_CONTRIBUTORS_FILE);
        Files.deleteIfExists(QUARKIVERSE_CONTRIBUTORS_FILE);
        Files.deleteIfExists(PLATFORM_CONTRIBUTORS_FILE);
        Files.deleteIfExists(WEBSITE_TRANSLATIONS_CONTRIBUTORS_FILE);
        Files.deleteIfExists(ALL_CONTRIBUTORS_FILE);

        final GitHub github = GitHub.connectAnonymously();

        Map<String, Contribution> allNameContributionMap = new HashMap<>();
        Map<String, Contribution> allEmailContributionMap = new HashMap<>();
        List<Contribution> allContributions = new ArrayList<>();

        System.out.println("Analyzing Quarkus main repository");

        Map<String, Contribution> quarkusNameContributionMap = new HashMap<>();
        Map<String, Contribution> quarkusEmailContributionMap = new HashMap<>();
        List<Contribution> quarkusContributions = new ArrayList<>();

        analyzeRepository(github.getRepository("quarkusio/quarkus"), ".", mainRepositoryBranch, since,
                allNameContributionMap, allEmailContributionMap, allContributions,
                quarkusNameContributionMap, quarkusEmailContributionMap, quarkusContributions);

        writeContributions(quarkusContributions, QUARKUS_CONTRIBUTORS_FILE, sort);

        // Get Quarkiverse contributors
        List<GHRepository> quarkiverseExtensions = github.searchRepositories()
                .org("quarkiverse")
                .topic("quarkus-extension")
                .order(GHDirection.ASC)
                .list().withPageSize(200).toList();

        Map<String, Contribution> quarkiverseNameContributionMap = new HashMap<>();
        Map<String, Contribution> quarkiverseEmailContributionMap = new HashMap<>();
        List<Contribution> quarkiverseContributions = new ArrayList<>();

        System.out.println("");
        System.out.println("Analyzing " + quarkiverseExtensions.size() + " Quarkiverse repositories");

        for (GHRepository quarkiverseExtension : quarkiverseExtensions) {
            analyzeRepository(quarkiverseExtension, ".", "main", since,
                    allNameContributionMap, allEmailContributionMap, allContributions,
                    quarkiverseNameContributionMap, quarkiverseEmailContributionMap, quarkiverseContributions);
        }

        writeContributions(quarkiverseContributions, QUARKIVERSE_CONTRIBUTORS_FILE, sort);

        // Get platform projects contributors
        System.out.println("");
        System.out.println("Analyzing " + PLATFORM_PROJECTS.size() + " Platform repositories");

        Map<String, Contribution> platformNameContributionMap = new HashMap<>();
        Map<String, Contribution> platformEmailContributionMap = new HashMap<>();
        List<Contribution> platformContributions = new ArrayList<>();

        for (Entry<String, String> otherProjectEntry : PLATFORM_PROJECTS.entrySet()) {
            GHRepository repository = github.getRepository(otherProjectEntry.getKey());
            analyzeRepository(repository, otherProjectEntry.getValue(), "main", since,
                    allNameContributionMap, allEmailContributionMap, allContributions,
                    platformNameContributionMap, platformEmailContributionMap, platformContributions);
        }

        writeContributions(platformContributions, PLATFORM_CONTRIBUTORS_FILE, sort);

        // Get website translations contributors
        List<GHRepository> websiteTranslationsRepositories = github.searchRepositories()
                .org("quarkusio")
                .topic("translation")
                .order(GHDirection.ASC)
                .list().withPageSize(200).toList();

        Map<String, Contribution> websiteTranslationsNameContributionMap = new HashMap<>();
        Map<String, Contribution> websiteTranslationsEmailContributionMap = new HashMap<>();
        List<Contribution> websiteTranslationsContributions = new ArrayList<>();

        System.out.println("");
        System.out.println("Analyzing " + websiteTranslationsRepositories.size() + " website translations repositories");

        for (GHRepository websiteTranslationsRepository : websiteTranslationsRepositories) {
            analyzeRepository(websiteTranslationsRepository, ".", "main", since,
                    allNameContributionMap, allEmailContributionMap, allContributions,
                    websiteTranslationsNameContributionMap, websiteTranslationsEmailContributionMap, websiteTranslationsContributions);
        }

        writeContributions(websiteTranslationsContributions, WEBSITE_TRANSLATIONS_CONTRIBUTORS_FILE, sort);

        writeContributions(allContributions, ALL_CONTRIBUTORS_FILE, sort);

        return 0;
    }

    private static void analyzeRepository(GHRepository repository, String root, String branch,
            Date since,
            Map<String, Contribution> allNameContributionMap,
            Map<String, Contribution> allEmailContributionMap,
            List<Contribution> allContributions,
            Map<String, Contribution> currentNameContributionMap,
            Map<String, Contribution> currentEmailContributionMap,
            List<Contribution> currentContributions) throws IOException, InterruptedException {
        System.out.println(" > Analyzing " + repository.getFullName());

        Path repositoryDirectory = CLONE_DIRECTORY.resolve(repository.getName());
        Process process;

        // if we already analyzed the repository, we don't clone it
        // and we redirect the contributions to other maps so that it doesn't get
        // counted twice for the contributors-all.csv file
        boolean alreadyAnalyzed = Files.exists(repositoryDirectory);

        if (!alreadyAnalyzed) {
            List<String> arguments = new ArrayList<>();
            arguments.add("git");
            arguments.add("clone");
            if (!"main".equals(branch)) {
                arguments.add("--branch");
                arguments.add(branch);
            }
            arguments.add(repository.getSshUrl());

            process = new ProcessBuilder(arguments.toArray(new String[0]))
                        .directory(CLONE_DIRECTORY.toFile())
                        .start();

            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();

            if (exitCode > 0) {
                throw new IllegalStateException("Error cloning " + repository.getFullName() + " - exit code: "
                        + exitCode + " - error: " + error);
            }
        }

        Path errorFile = repositoryDirectory.resolve("error.txt");
        Path contributionsFile = repositoryDirectory.resolve("contributions.txt");

        List<String> arguments = new ArrayList<>();
        arguments.add("git");
        arguments.add("--no-pager");
        arguments.add("log");
        arguments.add("--format=%an;%ae");
        arguments.add("--no-merges");
        arguments.add("--since");
        arguments.add(FORMATTER.format(since.toInstant()));
        if (!"main".equals(branch)) {
            arguments.add(branch);
        }
        arguments.add("--");
        arguments.add(root);

        process = new ProcessBuilder(arguments.toArray(new String[0]))
                        .directory(repositoryDirectory.toFile())
                        .redirectError(errorFile.toFile())
                        .redirectOutput(contributionsFile.toFile())
                        .start();

        process.waitFor();

        parseContributions(repository, contributionsFile,
                alreadyAnalyzed ? new HashMap<>() : allNameContributionMap,
                alreadyAnalyzed ? new HashMap<>() : allEmailContributionMap,
                alreadyAnalyzed ? new ArrayList<>() : allContributions,
                currentNameContributionMap, currentEmailContributionMap, currentContributions);
    }

    private static void parseContributions(GHRepository repository,
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
            String authorEmail = tokens.length > 1 ? tokens[1].trim().toLowerCase(Locale.ROOT) : "";

            if (ignore(authorName)) {
                continue;
            }

            pushContributions(repository, authorName, authorEmail, allNameContributionMap, allEmailContributionMap,
                    allContributions);
            pushContributions(repository, authorName, authorEmail, currentNameContributionMap, currentEmailContributionMap,
                    currentContributions);
        }
    }

    private static void pushContributions(GHRepository repository, String authorName, String authorEmail,
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

                Contribution sameEmailContribution = emailContributionMap.get(contribution.email);
                if (sameEmailContribution != null) {
                    sameEmailContribution.author = sameEmailContribution.author.length() > authorName.length() ? sameEmailContribution.author : authorName;
                    sameEmailContribution.commits += contribution.commits;
                    sameEmailContribution.username = "";

                    contributions.remove(contribution);
                    nameContributionMap.put(normalizedAuthorName, sameEmailContribution);
                } else {
                    emailContributionMap.put(authorEmail, contribution);
                }
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

    private static void writeContributions(List<Contribution> contributions, Path file, Sort sort) throws IOException {
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

    private static boolean ignore(String username) {
        if (username.contains(BOT)) {
            return true;
        }

        if (IGNORE_SET.contains(username)) {
            return true;
        }

        return false;
    }

    private static boolean isNoReply(String email) {
        return email.contains(NO_REPLY);
    }

    private static String extractUsername(String email) {
        String username = email.substring(0, email.indexOf('@'));
        if (username.contains("+")) {
            username = username.substring(email.indexOf('+') + 1);
        }
        return username;
    }

    public static class Contribution {

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
