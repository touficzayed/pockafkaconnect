# Token-Optimized Subagents Guide

This project includes specialized subagents designed to optimize token usage by matching the right model to each task type.

## Overview

| Agent | Model | Speed | Cost | Best For |
|-------|-------|-------|------|----------|
| **explorer** | Haiku | ⚡⚡⚡ | 💰 | Finding files, searching code, understanding structure |
| **tester** | Haiku | ⚡⚡⚡ | 💰 | Running tests, validation, CI checks |
| **code-reviewer** | Sonnet | ⚡⚡ | 💰💰 | Code quality, security, performance review |
| **debugger** | Sonnet | ⚡⚡ | 💰💰 | Fixing bugs, root cause analysis |
| **documenter** | Sonnet | ⚡⚡ | 💰💰 | Writing docs, guides, API documentation |
| **architect** | Opus | ⚡ | 💰💰💰 | Complex design decisions, system architecture |

## When to Use Each Agent

### 🔍 Explorer (Haiku) - Fastest, Cheapest

**Use when:**
- Finding specific files or functions
- Searching for code patterns
- Understanding project structure
- Locating where something is implemented
- Quick reconnaissance

**Example:**
```
Use the explorer subagent to find all files containing "PANTransformationSMT"
```

**Token cost:** Very low

---

### ✅ Tester (Haiku) - Fastest, Cheapest

**Use when:**
- Running test suites
- Checking if code compiles
- Validating builds
- Getting test results (pass/fail only)

**Example:**
```
Use the tester subagent to run all tests and report failures
```

**Token cost:** Very low

---

### 📝 Code-Reviewer (Sonnet) - Medium Speed, Medium Cost

**Use when:**
- Reviewing code after writing it
- Checking for security vulnerabilities
- Assessing code quality
- Performance analysis
- Verifying best practices

**Example:**
```
Use the code-reviewer subagent to review this transformer for security issues
```

**Token cost:** Medium (balanced capability and cost)

---

### 🐛 Debugger (Sonnet) - Medium Speed, Medium Cost

**Use when:**
- Tests are failing
- Getting error messages
- Finding root causes
- Fixing bugs
- Need to understand error stacks

**Example:**
```
Use the debugger subagent to fix the ClassNotFoundException error
```

**Token cost:** Medium

---

### 📚 Documenter (Sonnet) - Medium Speed, Medium Cost

**Use when:**
- Writing README files
- Creating user guides
- Documenting APIs
- Writing configuration guides
- Explaining architecture

**Example:**
```
Use the documenter subagent to write a guide for deploying with hourly file rotation
```

**Token cost:** Medium

---

### 🏗️ Architect (Opus) - Slowest, Most Expensive

**Use when:**
- Making complex design decisions
- Choosing between architectural approaches
- Planning large refactors
- Designing new features
- Analyzing system-wide implications
- Need deep reasoning and trade-off analysis

**Example:**
```
Use the architect subagent to design how to support decryption rules for 10 different banks
```

**Token cost:** High (but justified for complex decisions)

---

## Token Optimization Strategy

### Rule of Thumb: Right Tool for the Task

```
Task Type                    → Recommended Agent
├─ Find where something is   → explorer (Haiku)
├─ Does it compile?          → tester (Haiku)
├─ Is it secure/fast?        → code-reviewer (Sonnet)
├─ Why is it broken?         → debugger (Sonnet)
├─ How do I explain this?    → documenter (Sonnet)
└─ What should I build?      → architect (Opus)
```

### Cost Hierarchy

1. **Ultra-cheap (Haiku):** explorer, tester
   - Use frequently without worry
   - 5-10% of Opus cost

2. **Balanced (Sonnet):** code-reviewer, debugger, documenter
   - Use for detailed work
   - 30-50% of Opus cost

3. **Premium (Opus):** architect
   - Use for critical decisions only
   - Full cost, but highest reasoning

### Daily Workflow Example

```
Morning Code Sprint:
1. Write code
2. Use explorer to find related code → Haiku (cheap)
3. Use code-reviewer to review → Sonnet (balanced)
4. Use tester to run tests → Haiku (cheap)
5. Use debugger if tests fail → Sonnet (balanced)

Design Session:
1. Use architect to evaluate approaches → Opus (premium)
2. Use code-reviewer on final design → Sonnet (balanced)

Documentation:
1. Use documenter to write guide → Sonnet (balanced)
```

## How to Invoke Subagents

### Method 1: Explicit Request (Recommended)

```
Use the explorer subagent to find files matching pattern "Transform*"
```

### Method 2: Let Claude Decide

Just describe the task and Claude will delegate:

```
Find where the PANTransformationSMT is used in the codebase
```

Claude will automatically use explorer.

### Method 3: Interactive Agent Menu

```
/agents
```

Then select and chat with specific agents.

## Real Examples for This Project

### ✅ Good Token Usage

```
"Use explorer to find all imports of JWEHandler"
→ Haiku - 100 tokens

"Use code-reviewer to check if the demo producer is thread-safe"
→ Sonnet - 500 tokens

"Use architect to design multi-region deployment strategy"
→ Opus - 2000 tokens
```

### ❌ Bad Token Usage

```
"Use architect just to find where something is defined"
→ Wasting Opus cost, use explorer instead

"Use architect to run a test"
→ Wasting Opus on simple task, use tester instead

"Use tester to redesign encryption strategy"
→ Wrong tool, need architect for complex decisions
```

## Agent Capabilities by Feature

### For Kafka Connector Development

| Task | Agent | Why |
|------|-------|-----|
| Find where connector config is used | explorer | Fast pattern matching |
| Check if new connector deploys | tester | Validates builds |
| Review SMT security | code-reviewer | Checks for vulnerabilities |
| Debug deployment error | debugger | Analyzes stack traces |
| Design multi-connector strategy | architect | Complex trade-offs |
| Write setup guide | documenter | Clarity and examples |

### For Security/Encryption Work

| Task | Agent | Why |
|------|-------|-----|
| Find where PAN is handled | explorer | Quick search |
| Check test coverage | tester | Validation only |
| Review encryption approach | code-reviewer | Security focus |
| Fix JWE error | debugger | Root cause analysis |
| Design key rotation strategy | architect | High complexity |
| Document encryption guide | documenter | User-friendly |

## Tips for Maximum Efficiency

1. **Use explorer first** to understand before designing
2. **Use tester early** to catch issues fast
3. **Use code-reviewer before committing** to catch issues early
4. **Use architect sparingly** only for real complexity
5. **Use documenter at the end** to capture decisions
6. **Chain agents** - explorer → code-reviewer → tester

## Environment

All agents are stored in `.claude/agents/` and are version controlled with your project. This means:

✅ Your team uses the same agents
✅ Consistent standards across team
✅ Can improve agents over time
✅ New team members get the right tools

## Further Optimization

You can create more specialized agents for your specific needs:

```
database-analyzer.md     # Query optimization, schema analysis
performance-profiler.md  # Find bottlenecks
security-auditor.md      # Compliance and security audit
kafka-expert.md          # Kafka-specific patterns
java-tutor.md            # Learning/teaching Java patterns
```

Create them using `/agents` command or manually.
