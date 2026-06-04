# Security & quality scanning

All open-source tooling, wired via GitHub Actions (no build changes).

| Tool | Plugin (Java, open repo) | Bot (Go, private repo) | Catches |
|---|---|---|---|
| **Trivy** | ✅ `security.yml` | ✅ `ci.yml` | dependency CVEs, Dockerfile misconfig, leaked secrets |
| **OWASP Dependency-Check** | ✅ `security.yml` | — (Go support is weak) | known-vulnerable Java deps (NVD) |
| **govulncheck** | — | ✅ `ci.yml` | Go module + stdlib vulnerabilities (official) |
| **SonarQube** (Community / self-hosted) | ✅ `security.yml` | ✅ `ci.yml` | bugs, code smells, coverage, security hotspots |

On the **public plugin repo**, Trivy & Dependency-Check upload **SARIF** to GitHub code scanning (Security tab) — free for public repos. On the **private bot repo**, code-scanning SARIF upload would need GitHub Advanced Security (paid), so Trivy there prints a **table and fails the job** on CRITICAL/HIGH findings instead.

## Triggers
- **Plugin** (`.github/workflows/security.yml`): push to `develop`, PRs to `develop`/`master`, manual. Independent of the release workflow.
- **Bot** (`.github/workflows/ci.yml`): push/PR to `main`, manual. Also adds the missing Go build/vet/test baseline.

## Required GitHub config

SonarQube is **opt-in**: it only runs when the repo variable `SONAR_ENABLED` is `true`.

| Secret / Var | Where | Purpose |
|---|---|---|
| `SONAR_ENABLED` (variable) | both repos | set to `true` to enable the Sonar job |
| `SONAR_HOST_URL` (secret) | both repos | your self-hosted SonarQube CE URL |
| `SONAR_TOKEN` (secret) | both repos | SonarQube project/analysis token |
| `NVD_API_KEY` (secret) | plugin repo | optional — speeds up Dependency-Check's NVD update (request one free at nvd.nist.gov) |

> SonarQube Community Edition is the open-source, self-hostable option (works for the private bot repo too). For the **public** plugin repo, SonarCloud is a zero-infra free alternative — point `SONAR_HOST_URL` at `https://sonarcloud.io` and add the org/project, or swap to the SonarCloud action.

## Run locally
```bash
# Trivy (repo scan)
trivy fs --scanners vuln,secret,misconfig --severity CRITICAL,HIGH .

# Go vulns (bot)
cd bot && go run golang.org/x/vuln/cmd/govulncheck@latest ./...

# Java deps (plugin) — via the Dependency-Check CLI
dependency-check --scan . --project prefabs-uploader-plugin --failOnCVSS 8
```

## Tuning
- Severity gates: Trivy is set to `CRITICAL,HIGH` + `ignore-unfixed`; Dependency-Check fails on CVSS ≥ 8. Adjust in the workflows.
- Generated code (`gen/**`, `…/grpc/**`) is excluded from Sonar.
