#!/bin/sh
cd /srv/ssd1/workspace/Udea/.claude/worktrees/agent-af0ca178d38106b85
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew build --continue --no-configuration-cache --max-workers=6 --console=plain
echo "EXIT=$?"
echo DONE
