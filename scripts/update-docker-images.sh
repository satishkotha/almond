#!/usr/bin/env bash
set -euv

# TODO Convert to a mill task

echo "in update-docker-images.sh"

SCALA212_VERSION="$(./mill scala212)"
SCALA213_VERSION="$(./mill scala213)"

echo "SCALA212_VERSION=$SCALA212_VERSION"
echo "SCALA213_VERSION=$SCALA213_VERSION"

ALMOND_VERSION="$(git describe --tags --abbrev=0 --match 'v*' | sed 's/^v//')"

echo "ALMOND_VERSION=$ALMOND_VERSION"

DOCKER_REPO=almondsh/almond

echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin

TAG="$(git describe --exact-match --tags --always "$(git rev-parse HEAD)" || true)"

echo "TAG=$TAG"

if [[ ${TAG} != v* ]]; then
  echo "Not on a git tag, creating snapshot image"
  ALMOND_VERSION="$(./mill show 'scala.scala-kernel['"$SCALA213_VERSION"'].publishVersion')"
  echo "ALMOND_VERSION=$ALMOND_VERSION"
  IMAGE_NAME=${DOCKER_REPO}:snapshot
  echo ./mill '__['"$SCALA213_VERSION"'].publishLocal'
  ./mill '__['"$SCALA213_VERSION"'].publishLocal'
  echo ./mill '__['"$SCALA212_VERSION"'].publishLocal'
  ./mill '__['"$SCALA212_VERSION"'].publishLocal'
  cp -r $HOME/.ivy2/local/ ivy-local/
  docker build --build-arg ALMOND_VERSION=${ALMOND_VERSION} --build-arg=LOCAL_IVY=yes \
    --build-arg SCALA_VERSIONS="$SCALA213_VERSION $SCALA212_VERSION" -t ${IMAGE_NAME} .
  docker push ${IMAGE_NAME}
else
  echo "Creating release images for almond ${ALMOND_VERSION}"
  IMAGE_NAME=${DOCKER_REPO}:${ALMOND_VERSION}
  docker build --build-arg ALMOND_VERSION=${ALMOND_VERSION} \
    --build-arg SCALA_VERSIONS="$SCALA213_VERSION $SCALA212_VERSION" -t ${IMAGE_NAME} .
  docker build --build-arg ALMOND_VERSION=${ALMOND_VERSION} \
    --build-arg SCALA_VERSIONS="$SCALA213_VERSION" -t ${IMAGE_NAME}-scala-${SCALA213_VERSION} .
  docker build --build-arg ALMOND_VERSION=${ALMOND_VERSION} \
    --build-arg SCALA_VERSIONS="$SCALA212_VERSION" -t ${IMAGE_NAME}-scala-${SCALA212_VERSION} .

  docker push ${IMAGE_NAME}-scala-${SCALA213_VERSION}
  docker push ${IMAGE_NAME}-scala-${SCALA212_VERSION}
  docker push ${IMAGE_NAME}
  docker tag ${IMAGE_NAME} ${DOCKER_REPO}:latest
  docker push ${DOCKER_REPO}:latest
fi
