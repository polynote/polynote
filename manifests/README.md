# Kustomize Manifests

This directory contains an [off-the-shelf configuration](https://github.com/kubernetes-sigs/kustomize/blob/master/docs/glossary.md#off-the-shelf-configuration), useful for deploying Polynote Kubernetes resources to a cluster. This configuration can be referenced as a base for [bespoke configurations](https://github.com/kubernetes-sigs/kustomize/blob/master/docs/glossary.md#bespoke-configuration).

## Usage Instructions

```
kubectl kustomize github.com/polynote/polynote.git/manifest/base | kubectl apply -f -
```

## Bespoke Configuration Example

The following is an example of how a bespoke configuration may modify the provided off-the-shelf application.
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

bases:
- github.com/polynote/polynote.git/manifest/base

images:
- name: polynote/polynote
  newTag: 0.2.12-2.12

```
