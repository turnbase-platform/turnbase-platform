{{/*
Common labels, applied to every resource this chart creates.
*/}}
{{- define "turnbase.labels" -}}
app.kubernetes.io/part-of: turnbase
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end }}

{{/*
Selector labels for a named component (platform, gamehost, postgres, redis, minio, ...).
*/}}
{{- define "turnbase.selectorLabels" -}}
app.kubernetes.io/name: {{ .component }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end }}
