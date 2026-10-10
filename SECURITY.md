# Security Policy

## Supported Versions

Only the latest Stable release on JetBrains Marketplace is supported. Fixes
ship in a new release built from `main`.

## Reporting a Vulnerability

Report suspected vulnerabilities privately through GitHub's
[Report a vulnerability](https://github.com/cbusillo/jetbrains-inspection-api/security/advisories/new)
form. Do not open a public issue for a vulnerability.

Include the plugin version, IDE name and version, the impact, and the
smallest steps that reproduce it.

Do not send private source code, full inspection output from a private
project, tokens, or other personal data. Use a small made-up project instead.

This is a single-maintainer project. Reports are handled on a best-effort
basis, and I aim to reply within seven days.

## Scope

Relevant reports include:

- the HTTP API or bundled MCP server being usable by anyone other than the
  local IDE user;
- requests that read files or inspection results outside the open project or
  the requested scope;
- the plugin changing project files or IDE state when asked only to inspect;
  and
- dependency or GitHub Actions supply-chain problems.

Problems in the JetBrains IDE itself should go to JetBrains.
