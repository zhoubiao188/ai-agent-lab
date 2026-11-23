import { marked } from 'marked'
import hljs from 'highlight.js/lib/common'

marked.setOptions({
  gfm: true,
  breaks: true,
})

/** 把 Markdown 渲染成 HTML,并对代码块做高亮。 */
export function renderMarkdown(src: string): string {
  const html = marked.parse(src ?? '', { async: false }) as string
  // marked 输出的代码块形如 <pre><code class="language-java">,这里交给 highlight.js 染色
  return html.replace(
    /<pre><code class="language-([\w-]+)">([\s\S]*?)<\/code><\/pre>/g,
    (_m, lang: string, code: string) => {
      const decoded = decodeEntities(code)
      const language = hljs.getLanguage(lang) ? lang : 'plaintext'
      const highlighted = hljs.highlight(decoded, { language }).value
      return `<pre class="code-block"><div class="code-lang">${lang}</div><code class="hljs">${highlighted}</code></pre>`
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
