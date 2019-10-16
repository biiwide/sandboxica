# sandboxica

Don't play in the jungle.

## Usage

Require some namespaces:
```
(require '[biiwide.sandboxica.alpha :as sandbox]
	       '[amazonica.aws.ec2 :as ec2]
	       '[amazonica.aws.sqs :as sqs])
```

Intercept **all** Amazonica / AWS operations, and throw an exception:
```
(sandbox/with sandbox/always-fail
  (ec2/describe-instances {}))
```

Intercept **all** Amazonica / AWS operations, and return "nothing":
```
(sandbox/with sandbox/always-nothing
  (ec2/describe-instances {}))
```

A "nothing" value is usually `nil`, but can be `false`, `0`, or `NaN` for functions with primitive return types.

Use `always-nothing` by default, and _just_ implement `amazonica.aws.ec2/describe-instances`:
```
(sandbox/with (comp (sandbox/just
                      (ec2/describe-instances [req]
                        {:reservations [{:reservation-id "1"}
                                        {:reservation-id "2"}]}))
                    sandbox/always-nothing)
  (ec2/describe-instances {:filters []}))
```

## License

Copyright © 2019 Theodore Cushman

Distributed under the Eclipse Public License 2.0.

See the LICENSE file for details.
