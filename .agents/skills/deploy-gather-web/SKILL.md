---
name: deploy-gather-web
description: Deploy a reviewed Gather web and API revision to the existing Gather LAN Docker host, or preview which commit and running service a deployment would update.
---

Run this skill on the Gather Docker host from a Git checkout of this repository. The production installation defaults to `/home/nolan/dockers/gather`. Keep the checkout outside the `current` symlink and its release directories.

1. For the latest merged web version, run `./scripts/deploy-web --plan` and read the full source commit. For a specific revision, fetch it into the checkout if necessary and pass `--ref <ref-or-commit>` to the plan.
2. For a deployment request, run `./scripts/deploy-web --ref <full-commit-from-plan>`. For a preview request, report the plan. The script builds from tracked source, backs up the stopped service, checks the cloned data and switches the current release. Gather is briefly unavailable during the backup and switch.
3. Report the commit, health result and backup path from `current/deployment.json` and `current/source-commit`. On failure, report the staged release path and error. If the candidate accepted writes, keep its volume and the backup available for recovery.

The host stores API keys and deployment settings in the release's private `.env`. Keep those values out of chat, shell traces and logs. The historical `remote-deploy.py` targets the prototype data format. Use `scripts/deploy-web` for this service.
