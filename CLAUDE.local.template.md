# Local Development Configuration Template

> **Copy this file to `CLAUDE.local.md` and customize for your environment**
> **`CLAUDE.local.md` is in gitignore to keep local paths out of the repository**

## Test Project Configuration

### Project Paths
```bash
# Main test project path
TEST_PROJECT_PATH="/Users/[username]/Developer/[project-name]"
```

## IDE Configuration

### Development Environment
```bash
# IDE Details
IDE_TYPE="PyCharm"  # or IntelliJ, WebStorm, etc.
IDE_VERSION="2025.1"
IDE_PORT="63341"

# Plugin Installation Path
PLUGIN_DIR="~/Library/Application Support/JetBrains/PyCharm2025.1/plugins"

# Log Paths (update for your system)
IDE_LOG_PATH="~/Library/Logs/JetBrains/PyCharm2025.1/idea.log"

# Java 21 Home (optional - scripts will try to auto-detect)
# JAVA_HOME_21="/usr/lib/jvm/java-21-openjdk"
```

### Direct API Testing
```bash
# Test API endpoint
curl -s "http://localhost:$IDE_PORT/api/inspection/problems?severity=all" | jq '.'

# Test specific file
curl -s "http://localhost:$IDE_PORT/api/inspection/problems/path/to/file.py?severity=all" | jq '.'

# Test inspection categories
curl -s "http://localhost:$IDE_PORT/api/inspection/inspections" | jq '.'
```

## Development Notes

### Performance Baselines
```markdown
## Expected Performance
- Small project (< 100 files): < 5 seconds
- Medium project (< 1000 files): < 30 seconds
- Large project (> 1000 files): < 2 minutes
```

### Common Issues & Solutions
```markdown
## Troubleshooting

### Plugin Not Loading
1. Check IDE logs: `tail -f $IDE_LOG_PATH`
2. Verify plugin installation in: Preferences → Plugins
3. Restart IDE

### No Inspection Results
1. Verify inspection profile is enabled
2. Check the file is within the project scope
3. Ensure inspections are enabled for the file type
4. Wait for project indexing to complete
```