---
name: release
description: Make a new matchbox release (e.g. 4.1.19) - bump the version in the POMs and package.json, date the changelog, open and merge the release PR, run the release workflow and follow the Docker and Maven Central publishing. Use when the user asks to release, cut or publish a new matchbox version.
---

# Making a matchbox release

A release is one PR that only sets the version and the release date, then the `Create a release` workflow
(`.github/workflows/release.yml`), which creates the tag `vX.Y.Z` and the GitHub release with the notes of the version
from `docs/changelog.md`, and starts the Docker (`googleregistry.yml`) and Maven Central (`central_repository.yml`)
workflows on the tag.

The user asking for a release authorizes the whole procedure (PR, merge, release workflow). Stop and ask if a check
fails, if `main` has unexpected changes, or if the version isn't obvious.

Always work against `origin` (`ahdis/matchbox`), never `upstream` (the HAPI FHIR starter), and pass
`--repo ahdis/matchbox` to `gh`.

## 1. Check the state

```bash
git switch main && git pull --ff-only origin main && git fetch origin --tags
git tag --sort=-v:refname | head -3                      # last release
mvn -q help:evaluate -Dexpression=project.version -DforceStdout --batch-mode --no-transfer-progress; echo
head -1 docs/changelog.md                                # e.g. "2026/10/xx Release 4.1.19"
gh run list --repo ahdis/matchbox --commit $(git rev-parse HEAD)   # all green?
gh pr list --repo ahdis/matchbox --state open            # anything the user may want in the release?
```

The version to release is the one of the top section of `docs/changelog.md` (it's added with an `xx` day while the
changes are made); otherwise the patch version after the last tag. The POMs still have the previous version.

## 2. The release PR

On a branch `release-X.Y.Z`, set the version (`OLD` → `NEW`) and the date:

```bash
sed -i '' "s#<version>$OLD</version>#<version>$NEW</version>#" pom.xml matchbox-engine/pom.xml matchbox-server/pom.xml
(cd matchbox-frontend && npm version $NEW --no-git-tag-version)   # package.json and package-lock.json
sed -i '' "1s#^[0-9x/]* Release $NEW\$#$(date +%Y/%m/%d) Release $NEW#" docs/changelog.md
```

- Check with `git diff` that each POM changed only the project/parent `<version>`, not a dependency with the same
  version.
- The first line of the changelog must be exactly `YYYY/MM/DD Release X.Y.Z`: the release workflow finds the notes
  with this pattern, a date with `xx` isn't found.
- `mvn help:evaluate` (above) must print the new version: the release workflow compares it with its input.

Commit as `vX.Y.Z`, push, and open the PR titled `vX.Y.Z` (see PR #601 and #623), with the changes and what happens
after the merge in the body. Wait for all checks (`gh pr checks <nr> --repo ahdis/matchbox --watch`, about 20 min, in
the background).

## 3. Merge and release

1. Merge with rebase (merge commits are disabled on the repository):
   `gh pr merge <nr> --repo ahdis/matchbox --rebase --delete-branch`
2. The change of `matchbox-frontend/package.json` starts `Build the Angular GUI and commit` (`angular_build.yml`) on
   `main`, which rebuilds `matchbox-server/src/main/resources/static` and commits it if it changed. Wait until it has
   completed successfully (`gh run list --repo ahdis/matchbox --workflow angular_build.yml --limit 1`,
   `gh run watch <id> --repo ahdis/matchbox`): the release is made from the `main` commit at the time the workflow is
   started.
3. Run the release workflow:
   `gh workflow run release.yml --repo ahdis/matchbox --ref main -f version=X.Y.Z`
4. Follow the release run and then the two runs it starts on the tag (`googleregistry.yml`, `central_repository.yml`)
   until they complete: `gh run list --repo ahdis/matchbox --limit 5`, `gh run watch <id> --repo ahdis/matchbox`.
5. Check the release: `gh release view vX.Y.Z --repo ahdis/matchbox`.

Report the release URL and the outcome of the Docker and Maven Central workflows. Creating the tag and release by hand
in GitHub also starts these two workflows, if the release workflow can't be used.
