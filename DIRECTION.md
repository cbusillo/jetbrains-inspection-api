# Direction

This file is the current direction for the Inspection API JetBrains plugin
(`com.shiny.inspection.api`, Marketplace plugin 29580). The Director's overall
direction in `cbusillo/direction` wins over this file. When an issue,
milestone, or other document disagrees with this file, this file wins and the
other source is corrected or closed. Issues are a work list, not instructions.

## Purpose

Let people and their coding agents ask the IDE "is this code clean?" and trust
the answer. Use one codebase for two builds now: a Marketplace Stable build
that keeps the internal-API uses JetBrains has exempted, and a full build with
all proof and recovery features that the Director runs for Odoo work. Publish
the full build on a separate Marketplace channel and on GitHub releases.
Add a build switch that leaves only the non-exempted internal-API parts out of
Stable. As IntelliJ ships public APIs for those parts, move them onto public
APIs, with the goal of one build again. The plugin never says clean unless the
IDE really inspected every file with every enabled inspection and nothing
failed; when it cannot prove that, it says UNKNOWN. This work is spent from
the own-projects share.

Judge every change by one question: does this make the answers more
trustworthy, improve recovery, or bring Stable or one build again closer,
without adding internal APIs those features do not need?

## Stop Boundaries

An agent asks the Director before:

- publishing, replacing, withdrawing, or hiding any Marketplace update
- writing anything to JetBrains: YouTrack, the `intellij-community`
  repository, or Marketplace support
- adding to Stable a use of an IntelliJ API that the Plugin Verifier reports
  as internal and JetBrains has not exempted
- changing the plugin ID, vendor, license, or supported IDE range

Everything else is ordinary engineering and needs no ceremony.

## Journey

Someone finds and installs the plugin from the normal Marketplace Stable
listing and gets a true clean or findings verdict when public APIs and exempted
internal uses allow it. Someone who needs the full proof and recovery features
gets the full build from its Marketplace channel or GitHub releases. The
Director's agent inspects an Odoo worktree with that full build and gets a
decisive clean or findings verdict that is true. Both builds say UNKNOWN when
proof is missing. The Stable-exception
follow-up in [#401](https://github.com/cbusillo/jetbrains-inspection-api/issues/401)
still goes out so newly exempted uses can move into Stable; the two-build plan
does not wait for the reply. Whatever blocks that journey is the next piece of
work.

## Retired

- Every Code as the host this repository is built for
- the private plugin-recommendation canary and guarded plugin installation
  built on it
- weakening the rule for a truthful verdict to win Marketplace approval

## Milestones

- `Stable accepted on Marketplace` proves a Stable build with only exempted
  internal-API uses is approved and listed, while the full build is available
  on a separate Marketplace channel and GitHub releases and the Director runs
  it. Both keep the rule for a truthful verdict; ends if JetBrains refuses the
  Stable build for a reason the plugin cannot fix.
- `Inspection completion API upstream` proves IntelliJ ships public APIs to
  run export inspections in a normal IDE and to learn that each inspection in
  a run finished or failed, so the proof moves onto public APIs toward one build
  again; ends if JetBrains rejects both upstream changes.
