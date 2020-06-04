import {StateHandler} from "./state_handler";

interface EphemeralState {
    about?: string
}

export class EphemeralStateHandler extends StateHandler<EphemeralState>{

    private static inst: EphemeralStateHandler;
    static get get() {
        if (!EphemeralStateHandler.inst) {
            EphemeralStateHandler.inst = new EphemeralStateHandler({})
        }
        return EphemeralStateHandler.inst;
    }
}