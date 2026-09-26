# Direction

This file is the current direction for the Inspection API JetBrains plugin
(`com.shiny.inspection.api`, Marketplace plugin 29580). The owner's overall
direction in `cbusillo/direction` wins over this file. When an issue,
milestone, or other document disagrees with this file, this file wins and the
other source is corrected or closed. Issues are a work list, not instructions.

## Purpose

Let people and their coding agents ask the IDE "is this code clean?" and trust
the answer, from one build that is both the owner's own lint and type check
for Odoo work and a plugin anyone can find on the JetBrains Marketplace. The
plugin never says clean unless the IDE really inspected every file with every
enabled inspection and nothing failed; when it cannot prove that, it says
UNKNOWN. This work is spent from the own-projects share.

Judge every change by one question: does this make the answers more
trustworthy, or bring that same build to Stable sooner, without adding
internal APIs the truth does not need?

## Stop Boundaries

An agent asks the owner before:

- publishing, replacing, withdrawing, or hiding any Marketplace update
- writing anything to JetBrains: YouTrack, the `intellij-community`
  repository, or Marketplace support
- adding a use of an IntelliJ API that the Plugin Verifier reports as internal
- shipping a Stable build that differs from the build the owner runs
- changing the plugin ID, vendor, license, or supported IDE range

Everything else is ordinary engineering and needs no ceremony.

## Journey

Someone finds and installs the plugin from the normal Marketplace Stable
listing, and the owner's agent inspects an Odoo worktree with that same build
and gets a decisive clean or findings verdict that is true. Whatever blocks
that journey is the next piece of work.

## Retired

- Every Code as the host this repository is built for
- the private plugin-recommendation canary and guarded plugin installation
  built on it
- removing the proof of a truthful verdict to win Marketplace approval
- a separate Marketplace channel as the way outside users get the plugin

## Milestones

- `Stable accepted on Marketplace` proves a Stable update that keeps the full
  proof of a truthful verdict is approved and listed, and the owner runs that
  exact build; ends if JetBrains refuses the exception, or has not answered
  four weeks after the owner sends the follow-up, and then a temporary Stable
  build without the new proof code ships while the full build stays local.
- `Inspection completion API upstream` proves IntelliJ ships public APIs to
  run export inspections in a normal IDE and to learn that each inspection in
  a run finished or failed, so the plugin needs no new internal API; ends if
  JetBrains rejects both upstream changes.
