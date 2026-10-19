# Publication privacy checklist

- Keep .local, .tools, .env, credentials, private notes and build output out of Git.
- .gitignore also excludes environment variants and common private-key/keystore files; .env.example remains a blank configuration template.
- Ignoring a file does not remove an already-tracked file or earlier copies from Git history.
- Historical evidence contains local paths, process IDs, generated dataset IDs and actual measurement timestamps. Review these before making the repository public; use a private repository if this metadata is sensitive.
- Do not publish .local/settings.json, database files, session cookies, API keys or personal CV/contact details.
- Check commit author email and existing Git history locally before publishing. No author/date rewrite has been performed.
- Preserve measurement provenance and limitations. Synthetic business dates are not development dates.
- AI investigation is a product feature. OFF/MOCK/LIVE labels and unverified-quality warnings must remain accurate.
- No GitHub repository has been created or uploaded by this cleanup.

The June demonstration is an additional synthetic dataset, not a replacement of the frozen evaluation corpus.
Historical reports and authorship disclosures have not been erased to imply a different origin or completion time.

## Check results

- No common plaintext OpenAI project-key or private-key-header signatures were found in the scanned working files. This is a limited pattern scan, not a guarantee that every possible secret is absent.
- Git tracks the blank .env.example, not .env, .local or .tools. No matching keystore files were tracked.
- The known CV email/phone were not found in the checked README/docs/source/scripts/data/eval paths.
- One explicit user-home path in the setup notes was replaced with <project-root>; original runtime logs were retained rather than falsified.
- Existing commit author metadata remains unchanged. No remote is configured.
- Full native PostgreSQL verification passed: 204 tests (133 unit/fixture/security and 71 integration), zero failures/errors/skips. See evidence/publication-verify.log for the actual run.
