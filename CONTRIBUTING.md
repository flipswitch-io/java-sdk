# Contributing to Flipswitch Java SDK

Thank you for your interest in contributing to the Flipswitch Java SDK!

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/YOUR_USERNAME/java-sdk.git`
3. Create a feature branch: `git checkout -b feature/your-feature`
4. Make your changes
5. Run tests: `mvn test`
6. Commit your changes: `git commit -m "Add your feature"`
7. Push to the branch: `git push origin feature/your-feature`
8. Create a Pull Request

## Development Setup

Requirements:
- Java 17+
- Maven 3.8+

```bash
# Build
mvn compile

# Run tests
mvn test

# Package
mvn package

# Run demo
mvn compile test-compile exec:java -Dexec.mainClass="io.flipswitch.examples.FlipswitchDemo" -Dexec.args="<your-api-key>"
```

## Code Style

- Follow standard Java conventions
- Use meaningful variable and method names
- Add Javadoc for public APIs

## Pull Request Guidelines

- Keep changes focused and atomic
- Write clear commit messages
- Include tests for new functionality
- Update documentation as needed
- Ensure all tests pass

## Reporting Issues

Please use GitHub Issues to report bugs or request features. Include:
- Java version
- Operating system
- Steps to reproduce
- Expected vs actual behavior

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
