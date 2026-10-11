# Agent Context Optimization & Javadoc Engineering Guide

**Status:** Active standard (2026-10-10)  
**Applicability:** Automately Core, all submodules, AI pair programmers, and human contributors.

---

## 1. High Information Density at a Fraction of the Token Cost

When an AI coding agent tries to understand a class or service by reading the `.java` implementation file, it has to ingest:
- 500–1,500 lines of boilerplate, loops, stream pipelines, error logging, and internal variables.
- That can easily consume **4,000 to 10,000 tokens** just to figure out what one class does.

In contrast, when you provide or inspect clean Javadoc / class skeletons (interface definitions, method signatures, parameter contracts, and Javadoc tags):
- The agent gets the exact purpose, parameter contracts, invariants, and thrown exceptions in **under 300 tokens**.
- The agent can hold the mental model of **20 different classes in context** for the exact same token cost as reading two full implementation files.

---

## 2. Javadoc Tags That Prevent Agent Hallucinations

Java is strongly typed, but types alone do not tell the full story. Standard Javadoc tags fill the exact gaps where LLMs typically introduce subtle bugs:

### `@throws` / `@exception`
Coding agents frequently forget checked vs. unchecked exceptions or wrap them improperly. Javadoc makes the failure contract explicit:
```java
/**
 * Resolves the tenant isolate configuration.
 *
 * @param tenantId the unique tenant identifier
 * @return the resolved runtime configuration
 * @throws EntityNotFoundException if the tenant does not exist
 * @throws TenantSuspendedException if the tenant account is locked
 */
TenantConfig resolveConfig(String tenantId) throws EntityNotFoundException, TenantSuspendedException;
```
With this contract in context, the agent writes proper `try`/`catch` handling or lets exceptions bubble up correctly on the first pass without guessing.

### `@param` & `@return` Nullability Contracts
Types like `String` or `Buffer` can be null unless documented or annotated. Explicit docstrings anchor the agent's behavior:
```java
/**
 * @param tenantId must not be null
 * @param timeoutMs execution timeout in milliseconds; non-positive implies default (30_000 ms)
 * @return Optional containing the active session, or Optional.empty() if expired or terminated
 */
Optional<Session> findSession(String tenantId, long timeoutMs);
```
With this contract, the agent will never hallucinate a raw `null` return or litter the codebase with redundant defensive null-checks.

### `@see` and `@link`
These create explicit semantic trails. If an agent is using symbol search or an LSP, `@see SecurityContext` tells it exactly where the related domain logic lives without having to grep through dozens of source files:
```java
/**
 * Validates outgoing RPC frames against tenant capability grants.
 *
 * @see automately.core.runtime.api.HostApis
 * @see automately.runner.protocol.server.NodeRpcDispatcher
 */
```

---

## 3. How to Leverage Javadocs Without Bloating Context

To get the performance boost without wasting tokens:

### 1. Never Feed Full HTML Javadoc Builds to the Model
Do **not** point an agent at generated HTML output (e.g., `target/reports/apidocs/index.html` or `target/site/apidocs/`). HTML markup is token-heavy and full of navigation CSS, JavaScript, headers, footers, and DOM baggage that waste context window space.

### 2. Use Skeleton / Header Extraction
When asking an agent to write code that integrates with existing services, provide only the interface or header signatures with Javadocs, not the underlying implementation bodies:

```java
public interface DiagnosticRunner {
    /**
     * Executes the rule engine against an active operational context.
     *
     * @param context the target runtime environment; must not be null
     * @return the resulting diagnostic report
     * @throws DiagnosticExecutionException if rule evaluation fails
     */
    DiagnosticReport run(ExecutionContext context) throws DiagnosticExecutionException;
}
```
An agent with access to clean interface contracts like this generates correct consumer or implementation code almost 100% of the time on the first try.

### 3. Let Language Servers (LSP) Provide It On Demand
In modern coding environments (such as OpenCode or VS Code backed by `jdtls` — Eclipse JDT Language Server), the agent does not even need documentation dumped into the prompt upfront.
When the agent hovers a symbol, inspects a definition, or uses code completion, `jdtls` automatically returns the Javadoc hover text in real time.

---

## 4. Documentation Standards for Automately Contributors

When writing or modifying Java code in Automately:
1. **Public APIs and SPIs Require Javadocs:** Any public interface in `automately-core` (especially under `automately.core.runtime.api.*`), `automately-runtime-js`, and `automately-runner-protocol` must have complete Javadocs specifying:
   - Purpose and invariants
   - `@param` nullability and ranges
   - `@return` behavior and edge cases
   - `@throws` all checked and significant runtime exceptions
   - `@see` links to related interfaces or implementations
2. **Verify Javadoc Syntax:** Ensure `mvn javadoc:aggregate -DskipITs` builds cleanly with zero doclint errors.
