#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ "$#" -eq 0 ]; then set -- :desktop:run; fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -classpath gradle/wrapper/gradle-wrapper.jar \
    org.gradle.wrapper.GradleWrapperMain "$@"
