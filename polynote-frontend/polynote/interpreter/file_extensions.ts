const FileExtensions: Record<string, string> = {
    "py": "python"
}

export function languageOfExtension(extension?: string): string | undefined {
    if (!extension)
        return undefined;
    return FileExtensions[extension] || extension;
}