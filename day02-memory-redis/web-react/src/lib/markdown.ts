import { marked } from 'marked'
import hljs from 'highlight.js/lib/common'

marked.setOptions({ gfm: true, breaks: true })

export function renderMarkdown(src: string): string {
  const html = marked.parse(src ?? '', { async: false }) as string
  return html.replace(
    /<pre><code class="language-([\w-]+)">([\s\S]*?)<\/code><\/pre>/g,
    (_m, lang: string, code: string) => {
      const decoded = decodeEntities(code)
      const language = hljs.getLanguage(lang) ? lang : 'plaintext'
      return `<pre class="code-block"><div class="code-lang">${lang}</div><code class="hljs">${
        hljs.highlight(decoded, { language }).value
      }</code></pre>`
    },
  )
}

function decodeEntities(s: string): string {
  return s
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
}
