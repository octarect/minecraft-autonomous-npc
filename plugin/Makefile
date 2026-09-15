GRADLE_USER_HOME := /tmp/.gradle

.PHONY: build
build:
	GRADLE_USER_HOME=$(GRADLE_USER_HOME) ./gradlew --stop 2>/dev/null; \
	GRADLE_USER_HOME=$(GRADLE_USER_HOME) ./gradlew build

.PHONY: clean
clean:
	GRADLE_USER_HOME=$(GRADLE_USER_HOME) ./gradlew clean

.PHONY: stop
stop:
	GRADLE_USER_HOME=$(GRADLE_USER_HOME) ./gradlew --stop
