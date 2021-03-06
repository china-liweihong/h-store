/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 2.0.12
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

namespace BerkeleyDB.Internal {

using System;
using System.Runtime.InteropServices;

internal class libdb_csharp {
  internal static string db_strerror(int errno) {
    string ret = libdb_csharpPINVOKE.db_strerror(errno);
    return ret;
  }

  internal static int log_compare(DB_LSN lsn0, DB_LSN lsn1) {
		return libdb_csharpPINVOKE.log_compare(DB_LSN.getCPtr(lsn0), DB_LSN.getCPtr(lsn1));
}

  internal static void __os_ufree(DB_ENV dbenv, IntPtr ptr) {
    libdb_csharpPINVOKE.__os_ufree(DB_ENV.getCPtr(dbenv), ptr);
  }

  internal static IntPtr __os_umalloc(DB_ENV dbenv, uint size) {
	return libdb_csharpPINVOKE.__os_umalloc(DB_ENV.getCPtr(dbenv), size);
}

  internal static uint alloc_dbt_arr(DB_ENV dbenv, int num_dbt, out IntPtr ptr) {
    uint ret = libdb_csharpPINVOKE.alloc_dbt_arr(DB_ENV.getCPtr(dbenv), num_dbt, out ptr);
    return ret;
  }

}

}
