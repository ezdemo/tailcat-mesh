export function escapeHtml(value: string): string {
  return value.replace(/[&<>'"]/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "'": "&#39;",
    '"': "&quot;"
  }[character] ?? character));
}

export function escapeAttribute(value: string): string {
  return escapeHtml(value);
}

export function option(value: string, label: string, selected: boolean): string {
  return `<option value="${escapeAttribute(value)}"${selected ? " selected" : ""}>${escapeHtml(label)}</option>`;
}

export function actionButton(
  action: string,
  label: string,
  iconMarkup: string,
  options: { kind?: "primary" | "secondary" | "ghost" | "danger"; busy?: boolean; disabled?: boolean; className?: string; busyLabel?: string } = {}
): string {
  const kind = options.kind ?? "secondary";
  const disabled = options.disabled || options.busy ? " disabled" : "";
  const className = options.className ? ` ${options.className}` : "";
  const content = options.busy
    ? `<span class="button-spinner" aria-hidden="true"></span><span>${escapeHtml(options.busyLabel ?? label)}</span>`
    : `${iconMarkup}<span>${escapeHtml(label)}</span>`;
  return `<button type="button" class="button button-${kind}${className}" data-action="${escapeAttribute(action)}"${disabled}>${content}</button>`;
}

export function statusText(value: string | null | undefined): string {
  return value && value.trim() ? value : "—";
}
