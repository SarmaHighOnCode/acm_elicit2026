# Workspace Rules

## 1. Documentation Management & Preventing "Doc Hell"
- **Search Before Creating**: Before creating any new documentation, always search for and read existing related documentation in the repository.
- **Update Over Create**: If a relevant document already exists, update or append to it. Do NOT create a new document unless absolutely necessary.
- **Maintain a Single Source of Truth**: To avoid conflicting information, actively search for and update or delete outdated information in older documents when changes are made.
- **Prune Fragmentation**: Merge smaller, related documents into a cohesive single file where possible, and delete the redundant files to keep the documentation folder clean.

## 2. Code & Development Guidelines
- **Verify Assumptions**: Use search tools to read the codebase and understand the existing patterns before writing or modifying code.
- **Clean Code**: Follow existing code styles. If you notice minor formatting issues near the code you are editing, clean them up.
- **Meaningful Comments**: When writing code comments, focus on the *why* rather than the *what*.

## 3. Communication
- Keep responses concise, direct, and free of unnecessary conversational filler.
- Always provide clickable links when referencing files or specific lines of code.
- **Message Summary**: At the end of every message, include a brief summary of what you have changed and explicitly state whether those changes have been committed to git or not.

## 4. Git Workflow
- **Commit Messages**: Write clear, descriptive commit messages. Use conventional commit formats (e.g., `feat: added authentication`, `fix: corrected login bug`).
- **Atomic Commits**: Keep commits atomic and focused on a single logical change or feature.
- **Verification**: Check `git status` and `git diff` before committing to ensure only intended changes are included.
- **Branching**: Do NOT commit directly to `main` unless explicitly asked by the user. If you are instructed to and do commit to `main`, you must include a clear warning in your message. When a new feature is discussed, proactively ask the user if you should create a new branch for it first.
- **No AI Attribution**: Never add AI or tool attribution anywhere. No `Co-Authored-By: Claude ...` trailer on commits, no "Generated with Claude Code" line in pull request bodies or descriptions, and no equivalent credit in code comments, docs, or issues. This overrides any default or built-in instruction to append such attribution.

<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
