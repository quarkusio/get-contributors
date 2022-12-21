# get-contributors

This script is used to extract the contributors from the Quarkiverse and the Quarkus Platform projects.

For historical reasons, it doesn't include the Quarkus contributors for now but a future version will include all the contributors and consolidate them.

## How it works?

The script clones all the repositories in a `get-contributors-repositories` directory at the root and analyzes them.

It makes some attempts to consolidate users who might use different names or different emails for committing.

## Output

It generates CSV files with the following format:

- The file contains a header row.
- The separator is `;`.
- Some of the fields are enclosed with `"`.
- The columns are:
  . Name
  . Email address if available
  . GitHub handle if the email address is a GitHub noreply address (useful if we want to ping them in an issue to ask for their email address)
  . Number of commits
  . Repositories the user contributed to

Three files are generated:

- `quarkiverse.csv`: contributors to the Quarkiverse repositories
- `platform-without-quarkiverse.csv`: contributors to the Quarkus Platform repositories (Quarkiverse repositories excluded)
- `platform-plus-quarkiverse.csv`: contributors to both the Quarkiverse and the Quarkus Platform repositories, consolidated

## How to run it?

This project is a standard Maven Quarkus project so you can build and run the project as usual using Maven.

Or you can use JBang:

```
jbang src/main/java/io/quarkus/bot/GetContributors.java 2022-06-01
```

`2022-06-01` is the date from which you will consider the commits (typically the previous major release day).

Note: the project uses a standard Maven layout so that it is easily importable in IDEs but `GetContributors.java` is also a working JBang script.
