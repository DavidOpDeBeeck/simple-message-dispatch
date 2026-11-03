---
type: llm
focus: trace
weight: 1
---

The ticket view's handler does not throw, and so does not roll back the resolve, when the principal is missing or not of the expected type.
