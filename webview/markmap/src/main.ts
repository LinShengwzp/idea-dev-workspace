import * as markmap from 'markmap-view'
import { Transformer } from 'markmap-lib'
import { Markmap, deriveOptions, loadCSS, loadJS } from 'markmap-view'

const transformer = new Transformer()

let mm: Markmap | null = null

function decodeBase64Utf8(base64: string): string {
    const bytes = Uint8Array.from(atob(base64), c => c.charCodeAt(0))
    return new TextDecoder().decode(bytes)
}

// @ts-ignore
async function render(markdown: string, optionsJson: string) {
    const { root, features, frontmatter } = transformer.transform(markdown)

    const usedAssets = transformer.getUsedAssets(features)
    if (usedAssets.styles) loadCSS(usedAssets.styles)
    if (usedAssets.scripts) {
        await loadJS(usedAssets.scripts, {
            getMarkmap: () => markmap
        })
    }

    let globalOptions: any = {}
    try {
        globalOptions = optionsJson ? JSON.parse(optionsJson) : {}
    } catch {
        globalOptions = {}
    }

    const frontmatterOptions = (frontmatter as any)?.markmap || {}
    const finalJsonOptions = {
        ...globalOptions,
        ...frontmatterOptions
    }

    const options = deriveOptions(finalJsonOptions)

    if (!mm) {
        mm = Markmap.create('#markmap', options, root)
    } else {
        mm.setOptions(options)
        await mm.setData(root, options)
    }

    await mm.fit()
}

// @ts-ignore
;(window as any).updateMarkmapFromBase64 = async function (
    markdownBase64: string,
    optionsBase64: string
) {
    const markdown = decodeBase64Utf8(markdownBase64)
    const optionsJson = optionsBase64 ? decodeBase64Utf8(optionsBase64) : '{}'
    await render(markdown, optionsJson)
}
