import math
import pandas as pd
import numpy as np
import platform
import pickle

# posix_ipc is required for POSIX shm on Linux and Mac
if platform.system() in ['Linux', 'Darwin']:
    import posix_ipc
import mmap

from omnetpp.internal import Gateway

import functools
print = functools.partial(print, flush=True)

def _load_pickle_from_shm(name_and_size : str):
    """
    Internal. Opens a shared memory object (region, file, content) in a platform-specific
    way, unpickles its whole content, and returns the loaded object.
    `name_and_size` should be a space-separated pair of an object name and an integer,
    which is the size of the named SHM object in bytes.
    """
    if not name_and_size:
        return None

    name, size = name_and_size.split(" ")
    size = int(size)

    if name == "<EMPTY>" and size == 0:
        return None

    system = platform.system()
    if system in ['Linux', 'Darwin']:
        mem = posix_ipc.SharedMemory(name)

        with mmap.mmap(mem.fd, mem.size) as mf:
            mf.write_byte(1)
            mf.seek(8)
            p = pickle.load(mf)

    elif system == 'Windows':
        # on Windows, the mmap module in itself provides shared memory functionality
        with mmap.mmap(-1, size, tagname=name) as mf:
            mf.write_byte(1)
            mf.seek(8)
            p = pickle.load(mf)
    else:
        raise RuntimeError("unsupported platform")

    return p


def _get_array_from_shm(name_and_size : str):
    """
    Internal. Opens a shared memory object (region, file, content) in a platform-specific
    way, and returns the whole contents of it as a np.array of doubles.
    `name_and_size` should be a space-separated pair of an object name and an integer,
    which is the size of the named SHM object in bytes.
    """
    if not name_and_size:
        return None

    name, size = name_and_size.split(" ")
    size = int(size)

    if name == "<EMPTY>" and size == 0:
        return np.array([])

    system = platform.system()
    if system in ['Linux', 'Darwin']:
        mem = posix_ipc.SharedMemory(name)

        if system == 'Darwin':
            # for some reason we can't directly np.memmap the shm file, because it is "unseekable"
            # but the mmap module works with it, so we just copy the data into np, and release the shared memory
            with mmap.mmap(mem.fd, length=mem.size) as mf:
                mf.write_byte(1)
                mf.seek(0)
                arr = np.frombuffer(mf.read(), dtype=np.double, offset=8, count=int(size/8))
        else:
            # on Linux, we can just continue to use the existing shm memory without copying
            with open(mem.fd, 'wb') as mf:
                mf.write(b"\1")
                mf.seek(0)
                arr = np.memmap(mf, dtype=np.double, offset=8, shape=(int(size/8),))

        # on Mac we are done with shm (data is copied), on Linux we can delete the name even though the mapping is still in use
    elif system == 'Windows':
        # on Windows, the mmap module in itself provides shared memory functionality. and we copy the data here as well.
        with mmap.mmap(-1, size, tagname=name) as mf:
            mf.write_byte(1)
            mf.seek(0)
            arr = np.frombuffer(mf.read(), dtype=np.double, offset=8, count=int(size/8))
    else:
        raise RuntimeError("unsupported platform")

    return arr


def get_results(filter_expression="", row_types=['runattr', 'itervar', 'config', 'scalar', 'vector', 'statistic', 'histogram', 'param', 'attr'], omit_unused_columns=True, start_time=-math.inf, end_time=math.inf):
    shmnames = Gateway.results_provider.getResultsPickle(filter_expression, list(row_types), bool(omit_unused_columns), float(start_time), float(end_time))
    results = _load_pickle_from_shm(shmnames[0])

    df = pd.DataFrame.from_records(results, columns=[
        "runID", "type", "module", "name", "attrname", "attrvalue",
        "value", "count", "sumweights", "mean", "stddev", "min", "max",
        "underflows", "overflows", "binedges", "binvalues", "vectime", "vecvalue"])

    df["binedges"] = df["binedges"].map(lambda v: np.frombuffer(v, dtype=np.double), na_action='ignore')
    df["binvalues"] = df["binvalues"].map(lambda v: np.frombuffer(v, dtype=np.double), na_action='ignore')

    def getter(v):
        # skip lines that aren't vectors
        if v is None or math.isnan(v):
            return v
        return _get_array_from_shm(shmnames[int(v)])

    df["vectime"] = df["vectime"].map(getter)
    df["vecvalue"] = df["vecvalue"].map(getter)

    if omit_unused_columns:  # maybe do this in Java?
        df.dropna(axis='columns', how='all', inplace=True)

    return df


def _append_metadata_columns(df, metadata, suffix):
    """
    Internal. Helper for _append_additional_data().
    """
    metadata_df = pd.DataFrame(metadata, columns=["runID", "name", "value"])
    metadata_df = pd.pivot_table(metadata_df, columns="name", aggfunc='first', index="runID", values="value")
    if metadata_df.empty:
        return df
    else:
        return df.join(metadata_df, on="runID", rsuffix=suffix)


def _append_additional_data(df, attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries):
    """
    Internal. Performs the pivoting and appending of additional run/result
    metadata as columns onto the result DataFrames.
    """
    if attrs is not None:
        attrs = pd.DataFrame(attrs, columns=["runID", "module", "name", "attrname", "attrvalue"])
        if not attrs.empty:
            attrs = pd.pivot_table(attrs, columns="attrname", aggfunc='first', index=["runID", "module", "name"], values="attrvalue")
            df = df.merge(attrs, left_on=["runID", "module", "name"], right_index=True, how='left')

    runs = list(df["runID"].unique())

    if include_itervars:
        shmname = Gateway.results_provider.getItervarsForRunsPickle(runs) # TODO
        itervars = _load_pickle_from_shm(shmname)
        df = _append_metadata_columns(df, itervars, "_itervar")
    if include_runattrs:
        shmname = Gateway.results_provider.getRunAttrsForRunsPickle(runs)
        runattrs = _load_pickle_from_shm(shmname)
        df = _append_metadata_columns(df, runattrs, "_runattr")
    if include_config_entries:
        shmname = Gateway.results_provider.getConfigEntriesForRunsPickle(runs)  # TODO
        entries = _load_pickle_from_shm(shmname)
        df = _append_metadata_columns(df, entries, "_config")
    elif include_param_assignments:  # param_assignments are a subset of config_entries
        shmname = Gateway.results_provider.getParamAssignmentsForRunsPickle(runs)  # TODO
        params = _load_pickle_from_shm(shmname)
        df = _append_metadata_columns(df, params, "_param")

    return df


def get_serial():
    return Gateway.results_provider.getSerial()


def get_runs(filter_expression="", include_runattrs=False, include_itervars=False, include_param_assignments=False, include_config_entries=False):
    shmname = Gateway.results_provider.getRunsPickle(filter_expression)
    runs = _load_pickle_from_shm(shmname)

    df = pd.DataFrame({"runID": runs})
    return _append_additional_data(df, None, include_runattrs, include_itervars, include_param_assignments, include_config_entries)


def get_runattrs(filter_expression="", include_runattrs=False, include_itervars=False, include_param_assignments=False, include_config_entries=False):
    shmname = Gateway.results_provider.getRunAttrsPickle(filter_expression)
    runattrs = _load_pickle_from_shm(shmname)

    df = pd.DataFrame(runattrs, columns=["runID", "name", "value"])
    return _append_additional_data(df, None, include_runattrs, include_itervars, include_param_assignments, include_config_entries)


def get_itervars(filter_expression="", include_runattrs=False, include_itervars=False, include_param_assignments=False, include_config_entries=False, as_numeric=False):
    shmname = Gateway.results_provider.getItervarsPickle(filter_expression)
    itervars = _load_pickle_from_shm(shmname)

    df = pd.DataFrame(itervars, columns=["runID", "name", "value"])

    if as_numeric:
        df["value"] = pd.to_numeric(df["value"], errors="coerce")
    return _append_additional_data(df, None, include_runattrs, include_itervars, include_param_assignments, include_config_entries)


def get_config_entries(filter_expression, include_runattrs=False, include_itervars=False, include_param_assignments=False, include_config_entries=False):
    shmname = Gateway.results_provider.getConfigEntriesPickle(filter_expression)
    configentries = _load_pickle_from_shm(shmname)
    df = pd.DataFrame(configentries, columns=["runID", "name", "value"])

    return _append_additional_data(df, None, include_runattrs, include_itervars, include_param_assignments, include_config_entries)


def get_scalars(filter_expression="", include_attrs=False, include_runattrs=False, include_itervars=False, include_param_assignments=False, include_config_entries=False, merge_module_and_name=False):
    shmname = Gateway.results_provider.getScalarsPickle(filter_expression, include_attrs)
    scalars, attrs = _load_pickle_from_shm(shmname)
    df = pd.DataFrame(scalars, columns=["runID", "module", "name", "value"])

    df =_append_additional_data(df, attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries)
    if merge_module_and_name:
        df.name = df.module + "." + df.name
    return df


def get_parameters(filter_expression="", include_attrs=False, include_runattrs=False, include_itervars=False, include_param_assignments=False, include_config_entries=False, merge_module_and_name=False, as_numeric=False):
    shmname = Gateway.results_provider.getParamValuesPickle(filter_expression, include_attrs)
    parameters, attrs = _load_pickle_from_shm(shmname)
    df = pd.DataFrame(parameters, columns=["runID", "module", "name", "value"])

    if as_numeric:
        df["value"] = pd.to_numeric(df["value"], errors="coerce")

    df = _append_additional_data(df, attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries)
    if merge_module_and_name:
        df.name = df.module + "." + df.name
    return df


def get_vectors(filter_expression="", include_attrs=False, include_runattrs=False, include_itervars=False, include_param_assignments=False, include_config_entries=False, merge_module_and_name=False, start_time=-math.inf, end_time=math.inf):
    shmnames = Gateway.results_provider.getVectorsPickle(filter_expression, include_attrs, start_time, end_time)
    vectors, attrs = _load_pickle_from_shm(shmnames[0])
    df = pd.DataFrame(vectors, columns=["runID", "module", "name", "vectime", "vecvalue"])

    def getter(v):
        return _get_array_from_shm(shmnames[int(v)])

    df["vectime"] = df["vectime"].map(getter)
    df["vecvalue"] = df["vecvalue"].map(getter)

    df = _append_additional_data(df, attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries)
    if merge_module_and_name:
        df.name = df.module + "." + df.name
    return df


def get_statistics(filter_expression="", include_attrs=False, include_runattrs=False, include_itervars=False, include_param_assignments=False, include_config_entries=False, merge_module_and_name=False):
    shmname = Gateway.results_provider.getStatisticsPickle(filter_expression, include_attrs)
    statistics, attrs = _load_pickle_from_shm(shmname)
    df = pd.DataFrame(statistics, columns=["runID", "module", "name", "count", "sumweights", "mean", "stddev", "min", "max"])

    df = _append_additional_data(df, attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries)
    if merge_module_and_name:
        df.name = df.module + "." + df.name
    return df


def get_histograms(filter_expression="", include_attrs=False, include_runattrs=False, include_itervars=False, include_param_assignments=False, include_config_entries=False, merge_module_and_name=False, include_statistics_fields=False):
    shmname = Gateway.results_provider.getHistogramsPickle(filter_expression, include_attrs)
    histograms, attrs = _load_pickle_from_shm(shmname)
    df = pd.DataFrame(histograms, columns=["runID", "module", "name", "count", "sumweights", "mean", "stddev", "min", "max", "underflows", "overflows", "binedges", "binvalues"])

    df["binedges"] = df["binedges"].map(lambda v: np.frombuffer(v, dtype=np.double), na_action='ignore')
    df["binvalues"] = df["binvalues"].map(lambda v: np.frombuffer(v, dtype=np.double), na_action='ignore')

    df = _append_additional_data(df, attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries)
    if merge_module_and_name:
        df.name = df.module + "." + df.name
    return df
