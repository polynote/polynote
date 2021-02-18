import {ErrorStateHandler} from "./error_state";
import {ServerErrorWithCause} from "../data/result";

afterEach(() => {
    ErrorStateHandler.clear()
})

describe("ErrorStateHandler", () => {
    test("handles both server and kernel errors", () => {
        const kernelError = new ServerErrorWithCause("KernelErrorClass", "kernel error message", [])
        ErrorStateHandler.addKernelError("foo", kernelError)

        const serverError = new ServerErrorWithCause("ServerErrorClass", "server error message", [])
        ErrorStateHandler.addServerError(serverError)

        expect(ErrorStateHandler.get.state).toEqual({
            serverErrors: [{err: serverError, id: "ServerError: ServerErrorClass"}],
            foo: [{err: kernelError, id: "KernelError: KernelErrorClass"}]
        })

        ErrorStateHandler.removeError(ErrorStateHandler.get.state.serverErrors[0])
        ErrorStateHandler.removeError(ErrorStateHandler.get.state.foo[0])

        expect(ErrorStateHandler.get.state).toEqual({ serverErrors: [] })
    })

    test("can be observed", async () => {
        const gotError = new Promise(resolve => {
            ErrorStateHandler.get.addObserver(errs => {
                resolve(errs)
            })
        })

        const serverError = new ServerErrorWithCause("ServerErrorClass", "server error message", [])
        ErrorStateHandler.addServerError(serverError)

        await expect(gotError).resolves.toEqual({serverErrors: [{err: serverError, id: "ServerError: ServerErrorClass"}]})
    })

    test("handles notebook renames", () => {
        const kernelError = new ServerErrorWithCause("KernelErrorClass", "kernel error message", [])
        ErrorStateHandler.addKernelError("foo", kernelError)

        expect(ErrorStateHandler.get.state).toEqual({
            serverErrors: [],
            foo: [{err: kernelError, id: "KernelError: KernelErrorClass"}]
        })

        ErrorStateHandler.notebookRenamed("foo", "bar")

        expect(ErrorStateHandler.get.state).toEqual({
            serverErrors: [],
            bar: [{err: kernelError, id: "KernelError: KernelErrorClass"}]
        })
    })
})