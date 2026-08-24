{{- define "kbap.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end }}
{{- define "kbap.fullname" -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{- define "kbap.api.fullname" -}}
{{- include "kbap.fullname" . }}-{{ .Values.api.name | default "api" }}
{{- end }}

{{- define "kbap.batch.fullname" -}}
{{- include "kbap.fullname" . }}-{{ .Values.batch.name | default "batch" }}
{{- end }}

{{- define "kbap.labels" -}}
helm.sh/chart: {{ include "kbap.name" . }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "kbap.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
