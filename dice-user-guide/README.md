# DICE User Guide

Consumer-facing documentation for DICE, written in AsciiDoc and rendered by the asciidoctor Maven
plugin. This is the "how do I use it?" half of the docs; `docs/design/` at the repository root is the
"why is it built this way?" half, and stays contributor-facing.

## Build

```bash
mvn -pl dice-user-guide generate-resources
```

Output lands in `target/generated-docs/index.html`. `process-resources` is the module's default goal,
so a bare `mvn` in this directory does the same thing.

For a PDF:

```bash
mvn -pl dice-user-guide generate-resources -P guide-pdf
```

## Markdown

To produce Markdown, install [Docling](https://github.com/docling-project/docling):

```bash
pip install docling
```

Then build without a table of contents and convert:

```bash
mvn -pl dice-user-guide generate-resources -Dasciidoctor.attributes='toc!'
docling ./target/generated-docs/index.html --from html --to md \
  --output ./target/generated-docs --image-export-mode placeholder
```

This writes `index.md` alongside the HTML.

## Publishing

`.github/workflows/deploy-docs.yml` ("Publish Docs") builds the guide and the aggregated Dokka API
docs and deploys both to the Embabel web server under a versioned path:

- `https://docs.embabel.com/dice/guide/<version>/index.html`
- `https://docs.embabel.com/dice/api-docs/<version>/index.html`

It runs in these cases:

| Trigger | When |
|---|---|
| `repository_dispatch` (`publish-docs`) | The Build workflow's `trigger-docs` job fires it after a green build on `main` that touched a `.adoc` file. |
| `push` to `main` | Any push touching `dice-user-guide/**/*.adoc`. |
| `push` of a `v*` tag | Builds the guide and API docs from that tag and publishes under its POM version. |
| `workflow_dispatch` | Manually, with environment / VM instance / zone and optional `release_tag`. |

For a release whose guide changes after the tag has been cut, merge its `.adoc` changes to `main` and run
"Publish Docs" manually with `release_tag=v<version>`. The workflow builds the code and API docs
from that tag, overlays the guide's AsciiDoc from `main`, checks that the tag matches the POM
version, and deploys under that release version. A normal `.adoc` push to `main` continues to
publish under the current development version.

Future `v*` tag pushes publish the guide from the tagged source automatically. GitHub does not
evaluate the `.adoc` path filter for tag pushes. The tag must match `v${project.version}`, or the
workflow stops before deployment. Adding this trigger does not rerun the already-pushed `v0.3.0`
tag, so use the manual command below for that release.

For 0.3.0, after the guide changes are merged:

```bash
gh workflow run deploy-docs.yml --ref main -f release_tag=v0.3.0
```

The build step is `mvn -B -Pguide-html,dokka package`, run from this directory so the parent pom
resolves on disk — which is what makes `${project.parent.basedir}` work for the dokka profile's
sibling-module source paths.

Required repository secrets: `GCP_SERVICE_ACCOUNT_CREDENTIALS` (deploy) and `PAT_TOKEN` (the
dispatch from Build).

Build the API docs locally with:

```bash
mvn -pl dice-user-guide -P guide-html,dokka package
```

Output lands in `target/dokka-aggregate`.

## Layout

| Path | Contents |
|---|---|
| `src/main/asciidoc/index.adoc` | The book: front matter, then `user-guide.adoc`. |
| `src/main/asciidoc/user-guide.adoc` | The section order. Each section is a directory with a `page.adoc`. |
| `overview/` | What DICE is, the architecture, the modules, the glossary. |
| `quickstart/` | One page: dependencies through first report. |
| `concepts/` | Five pages, read in order. |
| `how-to/` | Task-shaped pages, prerequisites first. |
| `features/` | One page per opt-in surface, activation condition first. |
| `reference/` | Configuration properties, package structure, extension points. |
| `production/` | What changes when this stops being a quickstart. |
| `support/` | Compatibility matrix and FAQ. |
| `resources/` | Links, background reading, license. |
| `src/main/resources/themes/` | Stylesheets. `theme-extensions.css` is copied to the output. |

A section's `page.adoc` is an aggregator that `include::`s its pages. Adding a page means adding a
file and one `include::` line.

## Attributes

`{dice-version}` and `{embabel-agent-version}` come from the build, so version numbers in the text
never go stale. Use them instead of writing a version literally.

Snippets in a `[source,xml,subs="attributes+"]` block get attribute substitution; plain source blocks
do not.

## Conventions

- **Feature pages open with their activation condition** — the exact property or bean, before
  anything else. A reader should never have to guess whether a feature is on.
- **How-to pages open with prerequisites** — modules, beans, and what must already be true.
- **Concept pages end with a "Try it now" block** that runs against the quickstart's setup.
- **Every snippet should compile.** Where a page's code is worth pinning down, back it with a test in
  `dice-integration-tests`.
- **Cross-reference rather than repeat.** One page owns each topic; the rest link to it.
- **Anchors are `<section>-<page>`**, e.g. `[[features-graph-storage]]`, so a reference from
  elsewhere reads as what it points at.

## Docs rule

Every feature PR ships its page here and its `docs/design/` delta. Consumer-visible behaviour updates
a page under `src/main/asciidoc/`; new rationale updates the design note; an opt-in feature gets a
feature page carrying its activation condition; a new property updates
`reference/configuration-properties.adoc`; a version-support change updates
`support/compatibility.adoc`.

Internal refactors, test-only changes and build changes are exempt. Reviewers ask one question: with
only this PR's docs, could a consumer use the feature?
