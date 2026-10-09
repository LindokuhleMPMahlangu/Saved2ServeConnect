# Day 02: Git quality gates

This document records the repository quality gate setup. The full project context and validation commands are in the main README.

## Summary

- ignore rules for build and editor artifacts
- GitHub Actions workflow for Maven validation
- CI enforcement on main branch pushes and pull requests

## Notes

The quality gate is deliberately narrow: it validates the Java build and tests without changing the MVP privacy expectations or expanding the project scope. See the README for the complete operational context.
