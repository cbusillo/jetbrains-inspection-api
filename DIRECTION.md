# Direction

This file is the current direction for the Inspection API JetBrains plugin
(`com.shiny.inspection.api`, Marketplace plugin 29580). The Director's overall
direction in `cbusillo/direction` wins over this file. When an issue,
milestone, or other document disagrees with this file, this file wins and the
other source is corrected or closed. Issues are a work list, not instructions.

## Purpose

Let people and their own tools ask the IDE "is this code clean?" and trust
the answer. The plugin serves outside users; agents in the Director's catalog
inspect through isolated headless native IDE workers, not this plugin. One
codebase makes two builds. The Marketplace Stable build uses public APIs only.
The full build adds the internal-API proof and recovery features, and ships on
the Marketplace `agents` channel and GitHub releases. The APIs that proof needs
stay private, and JetBrains has said tool failures are not theirs to report,
so the two builds stay two. The plugin never says clean unless the IDE really
inspected every file with every enabled inspection and nothing failed; when it
cannot prove that, it says UNKNOWN. This work is spent from the own-projects
share.

Judge every change by one question: does this make the answers more
trustworthy or keep the plugin working for its users, without adding internal
APIs the full build's proof does not need?

## Stop Boundaries

An agent asks the Director before:

- publishing, replacing, withdrawing, or hiding any Marketplace Stable update
- writing anything to JetBrains: YouTrack, the `intellij-community`
  repository, or Marketplace support
- adding to Stable an API use that the Plugin Verifier reports as internal
- changing the plugin ID, vendor, license, or supported IDE range

Everything else is ordinary engineering and needs no ceremony.

## Journey

Someone finds and installs the plugin from the normal Marketplace Stable
listing and gets a true clean or findings verdict where public APIs can prove
it. Someone who needs broad-scope proof gets the full build from its
Marketplace channel or GitHub releases. Release smokes drive each build
through the in-repository desktop client (`scripts/desktop-client`) and get a
decisive clean or findings verdict that is true. Both builds say UNKNOWN when
proof is missing, and both keep working on each new IntelliJ release soon
after it ships. Whatever blocks that journey is the next piece of work.

## Retired

- Every Code as the host this repository is built for
- the private plugin-recommendation canary and guarded plugin installation
  built on it
- weakening the rule for a truthful verdict to win Marketplace approval
- asking JetBrains for internal-API exceptions or a public inspection
  completion API, and merging the two builds back into one
- qualifying the plugin for the Director's agents; their inspection is the
  `jetbrains-inspection` skill's work

## Milestones

None. Stable 1.14.3 was approved and listed on October 24, 2026, which met
the Stable-on-Marketplace milestone. The upstream completion-API milestone
ended when JetBrains declined to report tool failures.
