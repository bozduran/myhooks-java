# Moving to a standalone repository

`refactor-java/` is a self-contained Maven project. This file records how to
turn it into its own Git repository.

## Option A — fresh repo (simple, new history)

```sh
mkdir -p /path/to/myhooks-java && cd /path/to/myhooks-java
git init
cp -r "/home/duran/Documents/Go projects/myhooks/refactor-java/." .
git add -A
git commit -m "chore: initial import of Java myhooks"
```

## Option B — preserve history (subtree split)

```sh
cd "/home/duran/Documents/Go projects/myhooks"
git subtree split -P refactor-java -b java-only
git remote add java <new-repo-url>
git push java java-only:master
```

## After extraction

1. `mvn package` — first run downloads dependencies (needs network).
2. `java -jar target/myhooks-0.1.0-SNAPSHOT.jar --help`.
3. Open `USER_STORIES.md` and continue from **US-01**.
4. `reference/` is read-only port source; delete it once US-17 is done.
