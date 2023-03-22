'use strict';

const icons: Record<string, Promise<SVGElement | HTMLImageElement>> = {};

function loadXML(uri: string): Promise<Document> {
    return new Promise((resolve, reject) => {
        const req = new XMLHttpRequest();
        req.open("GET", uri);
        req.onreadystatechange = () => {
            if (req.readyState == 4) {
                if (req.responseXML) {
                    try {
                        resolve(req.responseXML)
                    } catch (err) {
                        reject(new Error("Response was not an XML document"))
                    }
                } else {
                    reject(req.statusText)
                }
            }
        };
        req.send(null);
    });
}

export function loadIcon(name: string): Promise<SVGElement | HTMLImageElement> {

    if (name in icons)
        return icons[name];

    const srcUrl = `static/style/icons/fa/${name}.svg`;
    const promise = loadXML(srcUrl).then(
        doc => document.importNode(doc.documentElement, true) as any as SVGElement
    ).catch(reason => {
        console.warn(`Unable to load icon ${name} as SVG; icon colors may be off for dark mode`);
        const img = document.createElement("img");
        img.src = srcUrl;
        return img;
    });

    icons[name] = promise;
    return promise;
}