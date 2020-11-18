The code in this package has been copy-pasted from [`zio-interop-cats`](https://github.com/zio/interop-cats)
and pruned slightly. This was done because of a tangled web of version conflicts – long story short,
polynote currently needs cats-effect 2.0.0, and there is no interop for that cats version that works with
the latest zio.

This will be a temporary measure – eventually, the need for cats-effect will be eliminated, and this can
be cleaned up.