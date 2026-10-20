# Documentation

| I want to… | Start here |
|---|---|
| Try the product locally | [Repository README](../README.md#try-it-locally) |
| Install it in my company's cloud/data center | [Step-by-step company installation](deployment/company-installation.md) |
| Choose cloud services / identity | [Cloud profiles and integration boundaries](deployment/cloud-profiles.md) |
| Set my name, logo, colors and theme | [Runtime branding](deployment/branding.md) |
| Find a configuration key | [Configuration reference](deployment/configuration-reference.md) |
| Understand what runs where | [Architecture, technology placement and run sequence diagrams](architecture/system-overview.md) |
| Configure traffic from known requirements | [Consumer configuration guide](user-guide/configuration.md) |
| Test a browser journey | [Browser walkthrough](user-guide/browser-testing.md) |
| Use an AI editor / pipeline | [CLI and portable skill](user-guide/ai-editors.md) |
| Use the REST API | [API examples](api/v2-examples.md) |
| Diagnose deployment problems | [Troubleshooting](deployment/troubleshooting.md) |
| Add engines / storage / identity | [Extension guide](development/extending.md) |
| Reproduce the disposable kind proof | [Standalone verification](deployment/standalone.md#reproduce-the-disposable-local-browser-proof) |

The current user-facing standalone product takes precedence over historical planning notes.
Future architecture/integration seams are identified explicitly; a port or module alone is not
proof that a provider is implemented. Keep company configuration separate from test definitions
and keep both separate from credentials.
