'use strict';

const icons: Record<string, Promise<SVGElement>> = {};

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

export function loadIcon(name: string): Promise<SVGElement> {

    if (icons[name])
        return icons[name];

    const promise = loadXML(`static/style/icons/fa/${name}.svg`).then(
        doc =>document.importNode(doc.documentElement, true) as any as SVGElement
    );

    icons[name] = promise;
    return promise;
}