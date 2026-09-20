#!/bin/sh
cd /srv/ssd1/workspace/Udea/.claude/worktrees/agent-af0ca178d38106b85
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true \
  --max-workers=6 --console=plain
echo "EXIT=$?"
echo DONE
