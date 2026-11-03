---
tags: [smd]
max_turns: 40
timeout_seconds: 900
allowed_tools: [Read, Glob, Grep, Skill, Write, Edit, Bash]
---

In examples/ticket-service, email the assignee when a ticket is assigned. We have no mail system yet, so put it behind a port with an implementation that only logs.
