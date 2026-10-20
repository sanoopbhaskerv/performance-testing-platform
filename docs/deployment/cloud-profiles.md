# Map the platform to your cloud or data center

These are deployment mappings and configuration overlays, not claims that the repository has
provisioned/certified your account. Begin with the [company installation](company-installation.md).
The application uses Kubernetes, PostgreSQL and the S3 object-storage protocol.

| Infrastructure | Control plane / runners | Durable database | Artifact path supported by current code |
|---|---|---|---|
| AWS | Your EKS or compatible Kubernetes | Your PostgreSQL/RDS PostgreSQL | Private S3; projected web identity or scoped credentials |
| Azure | Your AKS or compatible Kubernetes | Your PostgreSQL service | Approved S3-compatible endpoint; native Blob API needs adapter |
| Google Cloud | Your GKE or compatible Kubernetes | Your PostgreSQL service | Approved S3-compatible endpoint; native GCS API needs adapter |
| On premises / private cloud | Your conforming Kubernetes and CNI | Your operator-owned PostgreSQL | Your S3-compatible service such as an approved appliance |

Managed PostgreSQL connectivity still requires the correct JDBC endpoint, TLS trust, role/schema,
firewall and backups. The chart never creates RDS, Azure databases, Cloud SQL, cloud networks or
buckets. The same source and image can run across providers when these contracts are supplied.

## AWS S3 and EKS IRSA

Associate **separate** IAM roles with the control-plane and runner service accounts using your
company IaC. Trust must name the exact cluster identity issuer, namespace and service account.
Let Helm create the Kubernetes service accounts and apply their annotations; avoid having a
second tool also create/own those same service-account resources.
Configure the approved S3 prefixes, encryption/KMS policy and access to the STS endpoints used by the SDKs.
The Java distribution includes the AWS SDK STS module needed for web-identity credentials. See
[AWS's service-account role procedure](https://docs.aws.amazon.com/eks/latest/userguide/associate-service-account-role.html)
and [Java SDK credential chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html).

Overlay on the company example, replacing all account/role/bucket coordinates:

```yaml
serviceAccount:
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::111122223333:role/performance-control-plane
runnerCell:
  serviceAccountAnnotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::111122223333:role/performance-runner
artifacts:
  bucket: YOUR_PRIVATE_BUCKET
  region: YOUR_AWS_REGION
  endpoint: ""
  runnerEndpoint: ""
  pathStyleAccess: false
  controlPlaneCredentialsSecret: ""
  runnerCredentialsSecret: ""
```

With `fullnameOverride=performance`, the control-plane service account is `performance-sa` in
`perf-platform`. The example runner is `perf-runner` in `perf-runners`. Inspect the rendered chart
before writing trust policies. Clear inherited credential Secret references when using workload
identity; otherwise environment credentials may take precedence. IRSA is an infrastructure
credential flow, separate from consumer API authentication.

## Azure, Google Cloud and S3-compatible providers

Use your organization's approved PostgreSQL and compatible S3 endpoint. Set `artifacts.endpoint`,
signing `region`, and the provider's required `pathStyleAccess`. Put access keys in separate
namespace-scoped Secrets, or use a compatible identity mechanism verified by your storage team.
Set `runnerEndpoint` if API Pods and Jobs take different storage routes.

Setting an Azure/GCP service-account annotation does **not** translate the S3 SDK into a native
Blob/GCS client. Native storage support needs implementations of the common artifact, browser-source
and dataset storage ports, with equivalent metadata, ownership, digest and retention semantics.
Do not remove result-integrity checks to accommodate a provider. Add compatibility tests against
the actual service before advertising it as supported.

Use your cluster's ingress controller/class and annotations. Deploy the company TLS certificates
and DNS through your normal platform process. The chart's private registry Secret references
work independently of the registry vendor; cluster-native registry integrations may supply access
instead. No particular cloud's node autoscaler is required; Karpenter is optional legacy scaffolding,
not a portable runner-placement guarantee.

## Private dependencies and restricted networks

Build in a network with access to approved Maven/npm/image repositories, or configure your internal
mirrors and pre-stage the required versions. Upload mode avoids Git/npm access during execution
for a self-contained reviewed Playwright test. Repository mode additionally needs approved Git
and package routes; private-repository credential support is still an extension.

Supply your organization CA trust to every runtime that makes TLS connections. An approved
NetworkPolicy-capable CNI must enforce the runner's allowed routes. Private target reachability
requires your network routing and DNS. Verify actual denied traffic; a successful kind demo does
not certify a cloud firewall or an ingress controller.

## Capabilities that require extensions

| Requested integration | Current extension contract |
|---|---|
| Human SSO / corporate claims | Authentication adapter plus `CallerContextProvider`, retaining team + scope authorization |
| Native non-S3 storage | `ArtifactStore`, `BrowserSourceStore`, dataset storage adapters |
| A different engine | `ExecutionEngineProvider` plus pinned runner implementing the common contracts |
| Remote team-cluster runners | Outbound runner-agent/lease protocol; not implemented |
| BrowserStack | Optional execution provider; not implemented |
| Additional target/policy approval rules | Target/admission domain ports and reviewed API behavior |

See [extending the product](../development/extending.md). Keep the stable versioned specification
and run state machine; cloud customization must not create a different UI/API/YAML domain model.
