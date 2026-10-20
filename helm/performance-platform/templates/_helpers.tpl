{{/*
Expand the name of the chart.
*/}}
{{- define "performance-platform.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "performance-platform.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart label.
*/}}
{{- define "performance-platform.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels — applied to every resource.
*/}}
{{- define "performance-platform.labels" -}}
helm.sh/chart: {{ include "performance-platform.chart" . }}
{{ include "performance-platform.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels — used in pod selectors and Services.
*/}}
{{- define "performance-platform.selectorLabels" -}}
app.kubernetes.io/name: {{ include "performance-platform.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
ServiceAccount name.
*/}}
{{- define "performance-platform.serviceAccountName" -}}
{{- printf "%s-sa" (include "performance-platform.fullname" .) }}
{{- end }}

{{/*
Database URL — prefer externalUrl if postgres is disabled.
*/}}
{{- define "performance-platform.databaseUrl" -}}
{{- if .Values.postgres.enabled }}
{{- printf "jdbc:postgresql://%s-postgres:5432/perfplatform" (include "performance-platform.fullname" .) }}
{{- else }}
{{- .Values.postgres.externalUrl }}
{{- end }}
{{- end }}

{{- define "performance-platform.databaseSecretName" -}}
{{- default (printf "%s-db-credentials" (include "performance-platform.fullname" .)) .Values.database.existingSecret -}}
{{- end -}}
