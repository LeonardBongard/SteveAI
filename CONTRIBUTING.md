# Contributing to Steve AI

Thank you for your interest in contributing to Steve AI! This document provides guidelines and information for contributors.

## Code of Conduct

- Be respectful and professional
- Focus on constructive feedback
- Help maintain a welcoming environment

## How to Contribute

### Reporting Bugs

1. **Check existing issues** - Search [GitHub Issues](https://github.com/YuvDwi/Steve/issues) first
2. **Create detailed report** - Include:
   - Minecraft version
   - Forge version
   - Steve AI version
   - Steps to reproduce
   - Expected vs actual behavior
   - Relevant logs from `logs/latest.log`

### Suggesting Features

1. Open an issue with `[Feature Request]` prefix
2. Describe the feature and use case
3. Explain why it would benefit users
4. Consider implementation complexity

### Submitting Code

1. **Fork the repository**
   ```bash
   git clone https://github.com/YourUsername/Steve.git
   cd Steve
   ```

2. **Create a feature branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **Make your changes**
   - Follow code style guidelines (below)
   - Add JavaDoc comments for public APIs
   - Test thoroughly

4. **Test your changes**
   ```bash
   ./gradlew build
   ./gradlew runClient  # Test in game
   ```

5. **Commit with clear messages**
   ```bash
   git commit -m "Add feature: descriptive message"
   ```

6. **Push and create PR**
   ```bash
   git push origin feature/your-feature-name
   ```
   Then open a Pull Request on GitHub

## Code Style Guidelines

### Java Style

- **Formatting**: 4-space indentation
- **Naming**:
  - Classes: `PascalCase`
  - Methods: `camelCase`
  - Constants: `UPPER_SNAKE_CASE`
  - Variables: `camelCase`
- **Comments**: JavaDoc for public methods
- **Line length**: Max 120 characters

### Example

```java
package com.steve.ai.action.actions;

import com.steve.ai.entity.SteveEntity;

/**
 * Action for mining resources.
 * Automatically finds ore veins and excavates them.
 */
public class MineBlockAction extends BaseAction {
    private static final int MAX_SEARCH_RADIUS = 32;

    private String blockType;
    private int targetCount;

    /**
     * Create a new mining action.
     *
     * @param steve The Steve entity performing the action
     * @param task Task parameters containing block type and count
     */
    public MineBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        // Implementation
    }
}
```

## Project Structure

```
src/main/java/com/steve/ai/
├── action/              # Action system
│   ├── actions/         # Concrete action implementations
│   ├── ActionExecutor.java
│   └── CollaborativeBuildManager.java
├── ai/                  # LLM integration
│   ├── GroqClient.java
│   ├── TaskPlanner.java
│   └── PromptBuilder.java
├── execution/           # Code execution engine
│   ├── CodeExecutionEngine.java
│   └── SteveAPI.java
├── entity/              # Steve entity logic
├── client/              # Client-side GUI
├── memory/              # World knowledge & memory
├── structure/           # Building system
└── config/              # Configuration
```

## Development Setup

### Prerequisites

- Java 17 (Adoptium JDK recommended)
- Git
- IDE (IntelliJ IDEA or Eclipse recommended)

### Setup Steps

1. **Clone repository**
   ```bash
   git clone https://github.com/YuvDwi/Steve.git
   cd Steve
   ```

2. **Import to IDE**
   - IntelliJ: Open `build.gradle` as project
   - Eclipse: `./gradlew eclipse` then import

3. **Run in development**
   ```bash
   ./gradlew runClient
   ```

## Adding New Features

### Adding a New Action

1. Create new class in `src/main/java/com/steve/ai/action/actions/`
2. Extend `BaseAction`
3. Implement required methods:
   - `onStart()` - Initialize action
   - `onTick()` - Execute per game tick
   - `onCancel()` - Cleanup on cancel
   - `getDescription()` - Human-readable description

4. Register in `ActionExecutor.createAction()`
5. Update `PromptBuilder` if needed for LLM awareness

### Adding a New Structure

**Procedural:**
1. Add generator method in `StructureGenerators.java`
2. Return `List<BlockPlacement>`

**Template:**
1. Build structure in Minecraft
2. Use Structure Block to save as `.nbt`
3. Place in `src/main/resources/structures/`
4. Name it appropriately (e.g., `lighthouse.nbt`)

### Adding New LLM Provider

1. Create client in `src/main/java/com/steve/ai/ai/`
2. Implement HTTP API calls
3. Add retry logic for reliability
4. Register in `TaskPlanner.getAIResponse()`
5. Add config option in `SteveConfig.java`

## Testing

### Manual Testing

1. **Build and run**
   ```bash
   ./gradlew build
   ./gradlew runClient
   ```

2. **Test scenarios**
   - Spawn multiple Steves
   - Test all actions (build, mine, combat)
   - Verify multi-agent collaboration
   - Check error handling
   - Monitor performance (FPS should stay at 60)

3. **Check logs**
   - View `logs/latest.log` for errors
   - Verify no exceptions
   - Check API call timings

### Performance Guidelines

- Maintain 60 FPS with 10 active Steves
- Keep action tick delay reasonable (default: 20 ticks)
- Avoid blocking the game thread
- Use async operations for LLM calls

## Documentation

### JavaDoc Requirements

All public classes and methods should have JavaDoc:

```java
/**
 * Brief description of what this does.
 *
 * <p>Optional detailed description with more context.</p>
 *
 * @param paramName Description of parameter
 * @return Description of return value
 * @throws ExceptionType When this exception occurs
 */
```

### Update README

If your change affects user-facing features:
- Update README.md with new commands/features
- Add examples if applicable
- Update configuration section if needed

## Pull Request Guidelines

### PR Description Template

```markdown
## Description
Brief description of changes

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Performance improvement
- [ ] Documentation update

## Testing
How you tested the changes

## Screenshots/Videos
If applicable, add screenshots or demo video

## Checklist
- [ ] Code follows style guidelines
- [ ] Added JavaDoc comments
- [ ] Tested in-game
- [ ] Build passes (`./gradlew build`)
- [ ] Updated README if needed
```

### Review Process

1. Maintainers will review your PR
2. Address any requested changes
3. Once approved, PR will be merged
4. Your contribution will be credited

## Questions?

- Open a [GitHub Issue](https://github.com/YuvDwi/Steve/issues)
- Tag with `question` label
- We'll respond as soon as possible

## Recognition

Contributors will be credited in:
- Release notes
- Contributors section of README
- Git commit history

Thank you for contributing to Steve AI!
